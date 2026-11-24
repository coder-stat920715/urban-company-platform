package com.urbancompany.platform.payment;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity @Table(name = "membership_plans")
@Getter @Setter
public class MembershipPlan extends BaseEntity {
    private String name;
    private BigDecimal price;
    @Column(name = "duration_days") private int durationDays;
    @Column(name = "discount_percent") private BigDecimal discountPercent;
    @Column(name = "zero_convenience_fee") private boolean zeroConvenienceFee = true;
    private boolean active = true;
}
