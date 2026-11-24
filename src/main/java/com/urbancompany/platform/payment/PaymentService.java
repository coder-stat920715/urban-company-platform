package com.urbancompany.platform.payment;

import com.urbancompany.platform.booking.Booking;
import com.urbancompany.platform.booking.BookingService;
import com.urbancompany.platform.booking.BookingStatus;
import com.urbancompany.platform.common.*;
import com.urbancompany.platform.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Payment gateway SIMULATOR: intents + HMAC-signed webhooks (UPI / CARD / NETBANKING). Wallet portions are debited at intent creation
 * (a hold) and restored if the gateway payment fails. Webhook handling is idempotent via payment_events.event_id.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private final PaymentRepository payments;
    private final BookingService bookings;
    private final WalletService wallets;
    private final MembershipService memberships;
    private final DomainEventPublisher events;
    private final JdbcClient jdbc;
    private final AppProperties props;

    public record Intent(String intentId, String status, BigDecimal totalAmount, BigDecimal walletAmount, BigDecimal gatewayAmount, String clientSecret, String method) {}

    @Transactional
    public Intent createBookingIntent(Long userId, Long bookingId, String method, boolean useWallet) {
        Booking b = bookings.get(bookingId);
        if (!b.getCustomerId().equals(userId)) throw BusinessException.forbidden("Not your booking");
        if (b.getStatus() != BookingStatus.PAYMENT_PENDING) throw BusinessException.conflict("INVALID_STATE", "Booking is not awaiting payment");
        if (!payments.findByBookingIdAndStatus(bookingId, "CREATED").isEmpty()) throw BusinessException.conflict("INTENT_EXISTS", "A payment is already in progress for this booking");
        BigDecimal total = b.getTotal();
        BigDecimal walletPart = useWallet ? Money.min(wallets.balance(userId), total) : Money.ZERO;
        BigDecimal gateway = Money.r(total.subtract(walletPart));
        Payment p = new Payment();
        p.setPurpose("BOOKING"); p.setBookingId(bookingId); p.setUserId(userId); p.setAmount(total); p.setWalletAmount(walletPart);
        p.setIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        p.setMethod(gateway.signum() == 0 ? "WALLET" : normalizeMethod(method));
        if (walletPart.signum() > 0) wallets.debit(userId, walletPart, "BOOKING_PAYMENT", b.getBookingCode(), "Wallet used for booking " + b.getBookingCode());
        if (gateway.signum() == 0) {
            p.setStatus("PAID"); p.setGatewayRef("wallet");
            payments.save(p);
            onBookingPaid(p, b);
        } else {
            p.setStatus("CREATED");
            payments.save(p);
        }
        return new Intent(p.getIntentId(), p.getStatus(), total, walletPart, gateway, "secret_" + p.getIntentId(), p.getMethod());
    }

    @Transactional
    public Intent createMembershipIntent(Long userId, Long planId, String method, boolean useWallet) {
        MembershipPlan plan = memberships.plan(planId);
        BigDecimal walletPart = useWallet ? Money.min(wallets.balance(userId), plan.getPrice()) : Money.ZERO;
        BigDecimal gateway = Money.r(plan.getPrice().subtract(walletPart));
        Payment p = new Payment();
        p.setPurpose("MEMBERSHIP"); p.setPlanId(planId); p.setUserId(userId); p.setAmount(plan.getPrice()); p.setWalletAmount(walletPart);
        p.setIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        p.setMethod(gateway.signum() == 0 ? "WALLET" : normalizeMethod(method));
        if (walletPart.signum() > 0) wallets.debit(userId, walletPart, "MEMBERSHIP", plan.getName(), "UC Plus purchase");
        if (gateway.signum() == 0) { p.setStatus("PAID"); p.setGatewayRef("wallet"); payments.save(p); onMembershipPaid(p); }
        else { p.setStatus("CREATED"); payments.save(p); }
        return new Intent(p.getIntentId(), p.getStatus(), plan.getPrice(), walletPart, gateway, "secret_" + p.getIntentId(), p.getMethod());
    }

    private String normalizeMethod(String m) {
        String u = m == null ? "UPI" : m.toUpperCase();
        if (!Set3.contains(u)) throw BusinessException.bad("INVALID_METHOD", "Method must be UPI, CARD or NETBANKING");
        return u;
    }
    private static final java.util.Set<String> Set3 = java.util.Set.of("UPI", "CARD", "NETBANKING");

    /** Builds a signed webhook body exactly like the (simulated) gateway would. Used by the simulate endpoint and tests. */
    public String buildWebhookBody(String intentId, boolean success) {
        return JsonUtil.toJson(Map.of("id", "evt_" + UUID.randomUUID().toString().replace("-", ""),
                "type", success ? "payment.succeeded" : "payment.failed", "intentId", intentId,
                "gatewayRef", "gw_" + UUID.randomUUID().toString().substring(0, 12), "failureReason", success ? "" : "Card declined (simulated)"));
    }

    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.payment().webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Transactional
    public void handleWebhook(String rawBody, String signature) {
        if (signature == null || !MessageDigest.isEqual(sign(rawBody).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8)))
            throw BusinessException.unauthorized("Invalid webhook signature");
        Map<String, Object> evt = JsonUtil.toMap(rawBody);
        String eventId = String.valueOf(evt.get("id"));
        int inserted = jdbc.sql("INSERT INTO payment_events(event_id, payload) VALUES (?,?) ON CONFLICT (event_id) DO NOTHING").param(eventId).param(rawBody).update();
        if (inserted == 0) { log.info("Duplicate webhook {} ignored", eventId); return; }
        Payment p = payments.findByIntentId(String.valueOf(evt.get("intentId"))).orElseThrow(() -> BusinessException.notFound("Payment intent"));
        if (!"CREATED".equals(p.getStatus())) { log.info("Payment {} already {}", p.getIntentId(), p.getStatus()); return; }
        boolean success = "payment.succeeded".equals(evt.get("type"));
        p.setGatewayRef(String.valueOf(evt.get("gatewayRef")));
        if (success) {
            p.setStatus("PAID");
            payments.save(p);
            if ("BOOKING".equals(p.getPurpose())) onBookingPaid(p, bookings.get(p.getBookingId())); else onMembershipPaid(p);
        } else {
            p.setStatus("FAILED");
            p.setFailureReason(String.valueOf(evt.get("failureReason")));
            payments.save(p);
            if (p.getWalletAmount().signum() > 0) wallets.credit(p.getUserId(), p.getWalletAmount(), "PAYMENT_FAILED_REVERSAL", p.getIntentId(), "Wallet hold released");
            events.publish(Topics.PAYMENT, "PAYMENT", p.getId(), "PAYMENT_FAILED", Map.of("userId", p.getUserId(), "intentId", p.getIntentId(), "bookingId", p.getBookingId() == null ? 0 : p.getBookingId()));
        }
    }

    private void onBookingPaid(Payment p, Booking b) {
        bookings.onPaymentConfirmed(b.getId(), p.getAmount(), p.getWalletAmount());
        events.publish(Topics.PAYMENT, "PAYMENT", p.getId(), "PAYMENT_SUCCEEDED",
                Map.of("userId", p.getUserId(), "bookingId", b.getId(), "amount", p.getAmount(), "method", p.getMethod(), "intentId", p.getIntentId()));
    }

    private void onMembershipPaid(Payment p) {
        memberships.activate(p.getUserId(), p.getPlanId());
        events.publish(Topics.PAYMENT, "PAYMENT", p.getId(), "MEMBERSHIP_ACTIVATED", Map.of("userId", p.getUserId(), "planId", p.getPlanId(), "amount", p.getAmount()));
    }

    public Payment byIntent(String intentId) { return payments.findByIntentId(intentId).orElseThrow(() -> BusinessException.notFound("Payment intent")); }
}
