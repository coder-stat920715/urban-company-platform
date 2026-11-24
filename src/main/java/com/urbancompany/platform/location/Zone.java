package com.urbancompany.platform.location;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "zones")
@Getter @Setter
public class Zone extends BaseEntity {
    @Column(name = "city_id") private Long cityId;
    private String name;
    @Column(name = "center_lat") private double centerLat;
    @Column(name = "center_lng") private double centerLng;
    @Column(name = "radius_km") private Double radiusKm;
    @Column(name = "polygon_coords", columnDefinition = "text") private String polygonCoords;
    private boolean active = true;
}
