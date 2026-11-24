package com.urbancompany.platform.dispatch;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "job_offers")
@Getter @Setter
public class JobOffer extends BaseEntity {
    private Long bookingId;
    private Long partnerId;
    @Enumerated(EnumType.STRING) private OfferStatus status = OfferStatus.OFFERED;
    private double score;
    private int radiusKm;
    private int roundNo;
    private Instant offeredAt;
    private Instant expiresAt;
    private Instant respondedAt;
}
