package com.urbancompany.platform.catalog;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity @Table(name = "rate_cards")
@Getter @Setter
public class RateCard extends BaseEntity {
    @Column(name = "service_id") private Long serviceId;
    @Column(name = "item_name") private String itemName;
    private BigDecimal price;
    private String unit = "piece";
    private String description;
    private boolean active = true;
}
