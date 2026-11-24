package com.urbancompany.platform.location;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "cities")
@Getter @Setter
public class City extends BaseEntity {
    private String name;
    private String state;
    private boolean active = true;
    @Column(name = "center_lat") private double centerLat;
    @Column(name = "center_lng") private double centerLng;
}
