package com.urbancompany.platform.partner;

import com.urbancompany.platform.location.PartnerGeoIndex;
import com.urbancompany.platform.notification.RealtimePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Ingests partner GPS heartbeats (REST or STOMP), indexes them in Redis GEO and relays them to the customer of an active job. */
@Service
@RequiredArgsConstructor
public class PartnerTrackingService {
    private final PartnerRepository partners;
    private final PartnerGeoIndex geo;
    private final StringRedisTemplate redis;
    private final RealtimePublisher realtime;

    public record Heartbeat(double lat, double lng, Double heading, Double speedKmph) {}

    public void heartbeat(Long userId, Heartbeat hb) {
        Partner p = partners.findByUserId(userId).orElse(null);
        if (p == null || !p.isOnline()) return;
        geo.update(p.getId(), hb.lat(), hb.lng());
        String activeBooking = redis.opsForValue().get("partner:active-booking:" + p.getId());
        if (activeBooking != null) {
            realtime.toTopic("/topic/bookings/" + activeBooking + "/location",
                    Map.of("bookingId", Long.valueOf(activeBooking), "partnerId", p.getId(), "lat", hb.lat(), "lng", hb.lng(),
                            "heading", hb.heading() == null ? 0 : hb.heading(), "speedKmph", hb.speedKmph() == null ? 0 : hb.speedKmph(), "ts", Instant.now().toString()));
        }
    }

    public void bindActiveBooking(Long partnerId, Long bookingId) { redis.opsForValue().set("partner:active-booking:" + partnerId, String.valueOf(bookingId), Duration.ofHours(12)); }
    public void clearActiveBooking(Long partnerId) { redis.delete("partner:active-booking:" + partnerId); }
}
