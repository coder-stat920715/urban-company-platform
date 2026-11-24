package com.urbancompany.platform.cart;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity @Table(name = "surge_rules")
@Getter @Setter
public class SurgeRule extends BaseEntity {
    private String name;
    @Column(name = "zone_id") private Long zoneId;
    @Column(name = "start_hour") private int startHour;
    @Column(name = "end_hour") private int endHour;
    @Column(name = "day_of_week") private Integer dayOfWeek;
    private BigDecimal multiplier;
    private boolean active = true;
}
