package com.urbancompany.platform;

import com.urbancompany.platform.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test on real Postgres(PostGIS) + Redis + Kafka (Testcontainers): instant booking -> payment webhook -> geo dispatch ->
 * concurrent accept (no double assignment) -> OTP flow -> mid-job add-on -> completion -> Kafka/outbox earnings settlement.
 * Skipped automatically when Docker is unavailable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class BookingDispatchIntegrationTest {

    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("urbancompany").withUsername("uc").withPassword("uc");
    @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("app.seed-demo", () -> "true");
    }

    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newHttpClient();

    record Resp(int status, Map<String, Object> body) {
        @SuppressWarnings("unchecked") Map<String, Object> data() { return (Map<String, Object>) body.get("data"); }
        @SuppressWarnings("unchecked") List<Map<String, Object>> list() { return (List<Map<String, Object>>) body.get("data"); }
    }

    Resp call(String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
            if (token != null) b.header("Authorization", "Bearer " + token);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body() == null || r.body().isBlank() ? Map.of() : JsonUtil.toMap(r.body()));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    String login(String email) {
        Resp r = call("POST", "/api/v1/auth/login", null, Map.of("email", email, "password", "Demo@12345"));
        assertEquals(200, r.status(), "login " + email);
        return (String) r.data().get("accessToken");
    }

    long id(Object o) { return ((Number) o).longValue(); }

    @Test
    void instantBookingFullLifecycleWithConcurrentAcceptAndSettlement() throws Exception {
        String customer = login("customer@demo.local"), partner = login("partner1@demo.local"), partner2 = login("partner2@demo.local");

        // partner works round the clock and goes online near the customer (Koramangala)
        var hours = IntStream.rangeClosed(1, 7).mapToObj(d -> Map.of("dayOfWeek", d, "startTime", "00:00:00", "endTime", "23:59:00")).toList();
        assertEquals(200, call("PUT", "/api/v1/partner/working-hours", partner, hours).status());
        assertEquals(200, call("PUT", "/api/v1/partner/availability", partner, Map.of("online", true, "lat", 12.9352, "lng", 77.6245)).status());

        // customer: address (geofenced) -> cart -> instant checkout
        Resp addr = call("POST", "/api/v1/addresses", customer, Map.of("label", "Home", "line1", "1 Test Street", "lat", 12.9360, "lng", 77.6250, "makeDefault", true));
        assertEquals(200, addr.status(), String.valueOf(addr.body()));
        long addressId = id(addr.data().get("id"));
        call("DELETE", "/api/v1/cart", customer, null);
        assertEquals(200, call("POST", "/api/v1/cart/items", customer, Map.of("packageId", 8, "quantity", 1, "optionKeys", List.of("LEAKING_TAP"))).status());
        Resp checkout = call("POST", "/api/v1/bookings/checkout", customer, Map.of("addressId", addressId, "instant", true));
        assertEquals(200, checkout.status(), String.valueOf(checkout.body()));
        long bookingId = id(checkout.data().get("id"));
        String startOtp = (String) checkout.data().get("startOtp"), endOtp = (String) checkout.data().get("endOtp");
        assertEquals("PAYMENT_PENDING", checkout.data().get("status"));

        // payment intent + simulated gateway webhook (HMAC signed)
        Resp intent = call("POST", "/api/v1/payments/intents", customer, Map.of("bookingId", bookingId, "method", "UPI", "useWallet", false));
        assertEquals(200, intent.status(), String.valueOf(intent.body()));
        String intentId = (String) intent.data().get("intentId");
        assertEquals(200, call("POST", "/api/v1/payments/simulate/" + intentId + "?outcome=SUCCESS", customer, null).status());
        // webhook without a valid signature must be rejected
        assertEquals(401, call("POST", "/api/v1/payments/webhook", null, Map.of("id", "evt_x", "type", "payment.succeeded", "intentId", intentId)).status());

        // dispatch: partner1 receives an offer (30s TTL); partner2 is offline so gets none
        await().atMost(Duration.ofSeconds(20)).until(() -> !call("GET", "/api/v1/partner/offers", partner, null).list().isEmpty());
        long offerId = id(call("GET", "/api/v1/partner/offers", partner, null).list().get(0).get("offerId"));
        assertTrue(call("GET", "/api/v1/partner/offers", partner2, null).list().isEmpty());

        // concurrent accept: exactly one winner, no double assignment
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) results.add(pool.submit(() -> { go.await(); return call("POST", "/api/v1/partner/offers/" + offerId + "/accept", partner, null).status(); }));
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : results) codes.add(f.get(20, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(1, codes.stream().filter(c -> c == 200).count(), "exactly one accept wins: " + codes);
        assertEquals(1, codes.stream().filter(c -> c == 409).count(), "the other gets 409: " + codes);
        assertEquals("PARTNER_ASSIGNED", call("GET", "/api/v1/bookings/" + bookingId, customer, null).data().get("status"));

        // execution flow guarded by state machine + OTPs
        String job = "/api/v1/partner/jobs/" + bookingId;
        assertEquals(409, call("POST", job + "/start", partner, null).status(), "cannot start before en-route/OTP");
        assertEquals(200, call("POST", job + "/en-route", partner, null).status());
        assertEquals(200, call("POST", job + "/arrive", partner, null).status());
        assertEquals(400, call("POST", job + "/verify-start-otp", partner, Map.of("otp", "0000".equals(startOtp) ? "1111" : "0000")).status());
        assertEquals(200, call("POST", job + "/verify-start-otp", partner, Map.of("otp", startOtp)).status());
        assertEquals(200, call("POST", job + "/start", partner, null).status());

        // mid-job add-on: partner quotes, customer approves
        Resp extra = call("POST", job + "/extra-work", partner, Map.of("description", "Replace tap fitting", "amount", 250, "extraMinutes", 15));
        assertEquals(200, extra.status(), String.valueOf(extra.body()));
        assertEquals(409, call("POST", job + "/complete", partner, Map.of("otp", endOtp)).status(), "cannot complete while approval pending");
        assertEquals(200, call("POST", "/api/v1/bookings/" + bookingId + "/extra-work/" + id(extra.data().get("id")), customer, Map.of("approve", true)).status());
        assertEquals(200, call("POST", job + "/complete", partner, Map.of("otp", endOtp)).status());

        // Kafka consumer (via transactional outbox) writes the earnings ledger and settles the booking
        await().atMost(Duration.ofSeconds(40)).until(() -> "SETTLED".equals(call("GET", "/api/v1/bookings/" + bookingId, customer, null).data().get("status")));
        Map<String, Object> summary = call("GET", "/api/v1/partner/earnings/summary", partner, null).data();
        assertEquals(1, ((Number) summary.get("jobs")).intValue());
        assertTrue(new java.math.BigDecimal(String.valueOf(summary.get("net"))).signum() > 0);

        // review
        assertEquals(200, call("POST", "/api/v1/reviews", customer, Map.of("bookingId", bookingId, "rating", 5, "comment", "Great")).status());
        assertEquals(409, call("POST", "/api/v1/reviews", customer, Map.of("bookingId", bookingId, "rating", 4)).status(), "one review per booking");
    }

    @Test
    void multiCategoryCartMustBeSplitAtCheckout() {
        String customer = login("customer@demo.local");
        Resp addr = call("POST", "/api/v1/addresses", customer, Map.of("label", "Work", "line1", "2 Test Street", "lat", 12.9360, "lng", 77.6250));
        long addressId = id(addr.data().get("id"));
        call("DELETE", "/api/v1/cart", customer, null);
        assertEquals(200, call("POST", "/api/v1/cart/items", customer, Map.of("packageId", 1, "quantity", 1, "optionKeys", List.of("SPLIT"))).status());
        assertEquals(200, call("POST", "/api/v1/cart/items", customer, Map.of("packageId", 5, "quantity", 1, "optionKeys", List.of("STANDARD"))).status());
        Resp r = call("POST", "/api/v1/bookings/checkout", customer, Map.of("addressId", addressId, "instant", false));
        assertEquals(422, r.status());
        assertEquals("MULTI_CATEGORY_CART", r.body().get("code"));
        // required add-on question enforced
        assertEquals(422, call("POST", "/api/v1/cart/items", customer, Map.of("packageId", 1, "quantity", 1, "optionKeys", List.of())).status());
        // outside geofence is rejected
        assertEquals(422, call("POST", "/api/v1/addresses", customer, Map.of("label", "Far", "line1", "Delhi", "lat", 28.61, "lng", 77.20)).status());
    }
}
