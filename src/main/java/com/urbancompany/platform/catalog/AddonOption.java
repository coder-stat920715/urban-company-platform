package com.urbancompany.platform.catalog;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity @Table(name = "addon_options")
@Getter @Setter
public class AddonOption extends BaseEntity {
    @Column(name = "question_id") private Long questionId;
    @Column(name = "option_key") private String optionKey;
    private String label;
    @Column(name = "price_delta") private BigDecimal priceDelta = BigDecimal.ZERO;
    @Column(name = "duration_delta_minutes") private int durationDeltaMinutes;
    private boolean active = true;
}
