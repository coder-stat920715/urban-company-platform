package com.urbancompany.platform.admin;

import com.urbancompany.platform.common.JsonUtil;
import com.urbancompany.platform.common.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Append-only audit trail built from every domain event flowing through Kafka. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditEventConsumer {
    private final JdbcClient jdbc;

    @KafkaListener(topics = {Topics.BOOKING, Topics.DISPATCH, Topics.PAYMENT, Topics.EARNINGS, Topics.PARTNER}, groupId = "uc-audit")
    public void onEvent(String payload) {
        try {
            Map<String, Object> e = JsonUtil.toMap(payload);
            jdbc.sql("INSERT INTO audit_logs(actor, action, entity, entity_id, details) VALUES (?,?,?,?,?)")
                    .param(String.valueOf(e.getOrDefault("actor", "SYSTEM"))).param(String.valueOf(e.get("eventType")))
                    .param(String.valueOf(e.get("aggregateType"))).param(String.valueOf(e.get("aggregateId"))).param(payload).update();
        } catch (Exception ex) { log.warn("Audit write failed: {}", ex.toString()); }
    }
}
