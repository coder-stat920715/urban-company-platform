package com.urbancompany.platform.common;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "outbox_events")
@Getter @Setter
public class OutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "aggregate_type") private String aggregateType;
    @Column(name = "aggregate_id") private String aggregateId;
    @Column(name = "event_type") private String eventType;
    private String topic;
    @Column(columnDefinition = "text") private String payload;
    private String status = "NEW";
    private int attempts;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "sent_at") private Instant sentAt;
}
