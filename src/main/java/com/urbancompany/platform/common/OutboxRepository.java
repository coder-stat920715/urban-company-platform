package com.urbancompany.platform.common;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'NEW' ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> lockNextBatch();
}
