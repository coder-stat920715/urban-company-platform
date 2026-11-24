package com.urbancompany.platform.partner;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity @Table(name = "commission_tiers")
@Getter @Setter
public class CommissionTier extends BaseEntity {
    private String name;
    @Column(name = "min_jobs") private int minJobs;
    @Column(name = "commission_percent") private BigDecimal commissionPercent;
}
