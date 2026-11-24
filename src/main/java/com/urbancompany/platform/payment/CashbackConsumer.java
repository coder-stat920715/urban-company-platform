package com.urbancompany.platform.payment;

import com.urbancompany.platform.common.JsonUtil;
import com.urbancompany.platform.common.Money;
import com.urbancompany.platform.common.Topics;
import com.urbancompany.platform.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/** UC Plus members earn wallet cashback when a booking completes (idempotent per booking via wallet reference lookup). */
@Component
@RequiredArgsConstructor
public class CashbackConsumer {
    private final MembershipService memberships;
    private final WalletService wallets;
    private final AppProperties props;
    private final JdbcClient jdbc;

    @KafkaListener(topics = Topics.BOOKING, groupId = "uc-cashback")
    @Transactional
    public void onBookingEvent(String payload) {
        Map<String, Object> e = JsonUtil.toMap(payload);
        if (!"BOOKING_COMPLETED".equals(e.get("eventType"))) return;
        Long customerId = ((Number) e.get("customerId")).longValue();
        Long bookingId = ((Number) e.get("bookingId")).longValue();
        if (memberships.activePlan(customerId).isEmpty()) return;
        String ref = "cashback:booking:" + bookingId;
        Integer exists = jdbc.sql("SELECT count(*) FROM wallet_transactions WHERE reference = ?").param(ref).query(Integer.class).single();
        if (exists > 0) return;
        BigDecimal base = new BigDecimal(String.valueOf(e.get("grossAmount")));
        BigDecimal cashback = Money.pct(base, props.pricing().plusCashbackPercent());
        wallets.credit(customerId, cashback, "CASHBACK", ref, "UC Plus cashback");
    }
}
