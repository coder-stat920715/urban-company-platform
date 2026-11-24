package com.urbancompany.platform.location;

import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.common.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Geofencing: a point is serviceable when it lies inside an active zone's polygon (if defined) or radius. */
@Service
@RequiredArgsConstructor
public class ZoneService {
    private final ZoneRepository zones;
    private final CityRepository cities;

    public boolean contains(Zone z, double lat, double lng) {
        var poly = GeoUtils.parsePolygon(z.getPolygonCoords());
        if (poly.size() >= 3) return GeoUtils.inPolygon(lat, lng, poly);
        return z.getRadiusKm() != null && GeoUtils.haversineKm(z.getCenterLat(), z.getCenterLng(), lat, lng) <= z.getRadiusKm();
    }

    public Optional<Zone> resolve(double lat, double lng) {
        return zones.findByActiveTrue().stream()
                .filter(z -> cities.findById(z.getCityId()).map(City::isActive).orElse(false))
                .filter(z -> contains(z, lat, lng))
                .min((a, b) -> Double.compare(
                        GeoUtils.haversineKm(a.getCenterLat(), a.getCenterLng(), lat, lng),
                        GeoUtils.haversineKm(b.getCenterLat(), b.getCenterLng(), lat, lng)));
    }

    public Zone requireServiceable(double lat, double lng) {
        return resolve(lat, lng).orElseThrow(() -> BusinessException.unprocessable("NOT_SERVICEABLE", "We do not serve this location yet"));
    }
}
