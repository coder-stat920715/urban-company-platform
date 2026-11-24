package com.urbancompany.platform.review;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "warranty_claims")
@Getter @Setter
public class WarrantyClaim extends BaseEntity {
    private Long bookingId;
    private Long customerId;
    private String reason;
    private Instant preferredStart;
    private String status = "PENDING";    // PENDING | APPROVED (rebooked) | REFUNDED | REJECTED
    private String resolutionNote;
    private Long rebookingId;
}
