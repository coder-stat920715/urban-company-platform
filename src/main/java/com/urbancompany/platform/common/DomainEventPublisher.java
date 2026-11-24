package com.urbancompany.platform.common;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Transactional Outbox: writes the event in the caller's DB transaction; {@link OutboxRelay} ships it to Kafka. */
@Component
@RequiredArgsConstructor
public class DomainEventPublisher {
    private final OutboxRepository repo;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, String aggregateType, Object aggregateId, String eventType, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("eventType", eventType);
        payload.put("aggregateType", aggregateType);
        payload.put("aggregateId", String.valueOf(aggregateId));
        payload.put("occurredAt", Instant.now().toString());
        if (data != null) data.forEach((k, v) -> payload.put(k, v instanceof Instant ? v.toString() : v));
        OutboxEvent e = new OutboxEvent();
        e.setAggregateType(aggregateType);
        e.setAggregateId(String.valueOf(aggregateId));
        e.setEventType(eventType);
        e.setTopic(topic);
        e.setPayload(JsonUtil.toJson(payload));
        repo.save(e);
    }
}
