package com.urbancompany.platform.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Polls the outbox (SKIP LOCKED => safe with many instances) and publishes to Kafka with at-least-once semantics. */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {
    private final OutboxRepository repo;
    private final KafkaTemplate<String, String> kafka;

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = repo.lockNextBatch();
        for (OutboxEvent e : batch) {
            try {
                kafka.send(e.getTopic(), e.getAggregateId(), e.getPayload()).get(5, TimeUnit.SECONDS);
                e.setStatus("SENT");
                e.setSentAt(Instant.now());
            } catch (Exception ex) {
                e.setAttempts(e.getAttempts() + 1);
                log.warn("Outbox publish failed id={} attempt={} : {}", e.getId(), e.getAttempts(), ex.toString());
                if (e.getAttempts() >= 20) e.setStatus("FAILED");
                break; // preserve ordering; retry on next tick
            }
        }
    }
}
