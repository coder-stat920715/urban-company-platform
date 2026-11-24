package com.urbancompany.platform.partner;

import com.urbancompany.platform.booking.BookingService;
import com.urbancompany.platform.common.JsonUtil;
import com.urbancompany.platform.common.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/** Consumes BOOKING_COMPLETED from Kafka (published via outbox) -> writes the earnings ledger row -> settles the booking. */
@Component
@RequiredArgsConstructor
@Slf4j
public class EarningsEventConsumer {
    private final EarningsService earnings;
    private final BookingService bookings;

    @KafkaListener(topics = Topics.BOOKING, groupId = "uc-earnings")
    @Transactional
    public void onBookingEvent(String payload) {
        Map<String, Object> e = JsonUtil.toMap(payload);
        if (!"BOOKING_COMPLETED".equals(e.get("eventType"))) return;
        Long bookingId = ((Number) e.get("bookingId")).longValue();
        Long partnerId = ((Number) e.get("partnerId")).longValue();
        BigDecimal gross = new BigDecimal(String.valueOf(e.get("grossAmount")));
        boolean created = earnings.record(bookingId, partnerId, gross);
        bookings.markSettled(bookingId);
        log.info("Earnings recorded booking={} partner={} created={}", bookingId, partnerId, created);
    }
}
