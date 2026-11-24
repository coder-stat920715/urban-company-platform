package com.urbancompany.platform.catalog;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

import java.math.BigDecimal;

@Entity @Table(name = "service_packages")
@Getter @Setter
public class ServicePackage extends BaseEntity {
    @Column(name = "service_id") private Long serviceId;
    private String name;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "base_price") private BigDecimal basePrice;
    @Column(name = "duration_minutes") private int durationMinutes;
    @Column(name = "max_quantity") private int maxQuantity = 1;
    @Column(name = "instant_eligible") private boolean instantEligible;
    /** JSONB column: dynamic package attributes (warranty days, inclusions, ...). */
    @Column(name = "attributes_json", columnDefinition = "jsonb")
    @ColumnTransformer(read = "attributes_json::text", write = "?::jsonb")
    private String attributesJson = "{}";
    private boolean active = true;
}
