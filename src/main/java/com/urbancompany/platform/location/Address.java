package com.urbancompany.platform.location;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "addresses")
@Getter @Setter
public class Address extends BaseEntity {
    @Column(name = "user_id") private Long userId;
    private String label;
    private String line1;
    private String line2;
    private String landmark;
    @Column(name = "city_id") private Long cityId;
    @Column(name = "zone_id") private Long zoneId;
    private double lat;
    private double lng;
    @Column(name = "default_address") private boolean defaultAddress;
}
