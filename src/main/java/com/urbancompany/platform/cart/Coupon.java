package com.urbancompany.platform.cart;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name = "coupons")
@Getter @Setter
public class Coupon extends BaseEntity {
    private String code;
    @Column(name = "discount_type") private String discountType;     // PERCENT | FLAT
    @Column(name = "discount_value") private BigDecimal discountValue;
    @Column(name = "max_discount") private BigDecimal maxDiscount;
    @Column(name = "min_order") private BigDecimal minOrder = BigDecimal.ZERO;
    @Column(name = "valid_from") private Instant validFrom;
    @Column(name = "valid_to") private Instant validTo;
    @Column(name = "usage_limit") private Integer usageLimit;
    @Column(name = "per_user_limit") private int perUserLimit = 1;
    @Column(name = "used_count") private int usedCount;
    private boolean active = true;
}
