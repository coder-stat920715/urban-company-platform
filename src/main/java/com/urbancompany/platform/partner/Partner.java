package com.urbancompany.platform.partner;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "partners")
@Getter @Setter
public class Partner extends BaseEntity {
    @Column(name = "user_id") private Long userId;
    @Column(name = "display_name") private String displayName;
    @Enumerated(EnumType.STRING) private PartnerStatus status = PartnerStatus.PENDING_KYC;
    @Column(name = "zone_id") private Long zoneId;
    private boolean online;
    @Column(name = "rating_avg") private double ratingAvg;
    @Column(name = "rating_count") private int ratingCount;
    @Column(name = "acceptance_rate") private double acceptanceRate = 1.0;
    @Column(name = "offers_received") private int offersReceived;
    @Column(name = "offers_accepted") private int offersAccepted;
    @Column(name = "jobs_completed") private int jobsCompleted;
    @Column(name = "active_jobs") private int activeJobs;
    @Column(name = "bank_account_no") private String bankAccountNo;
    @Column(name = "bank_ifsc") private String bankIfsc;
    @Column(name = "bank_holder") private String bankHolder;
    @Version private Long version;
}
