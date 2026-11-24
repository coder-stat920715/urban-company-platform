package com.urbancompany.platform.booking;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name = "extra_work_requests")
@Getter @Setter
public class ExtraWorkRequest extends BaseEntity {
    private Long bookingId;
    private Long partnerId;
    private String description;
    private BigDecimal amount;
    private int extraMinutes;
    private String status = "PENDING";      // PENDING | APPROVED | REJECTED
    private Instant resolvedAt;
}
