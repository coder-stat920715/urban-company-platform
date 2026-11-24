package com.urbancompany.platform.admin;

import com.urbancompany.platform.common.ApiResponse;
import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.location.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** City and geofence (radius or polygon) setup. Polygon format: "lat,lng;lat,lng;lat,lng;..." (>= 3 vertices). */
@RestController
@RequestMapping("/api/v1/admin/locations")
@RequiredArgsConstructor
public class AdminLocationController {
    private final CityRepository cities;
    private final ZoneRepository zones;

    public record CityBody(String name, String state, double centerLat, double centerLng, Boolean active) {}
    public record ZoneBody(Long cityId, String name, double centerLat, double centerLng, Double radiusKm, String polygonCoords, Boolean active) {}

    @GetMapping("/cities") public ApiResponse<List<City>> cities() { return ApiResponse.ok(cities.findAll()); }
    @PostMapping("/cities") public ApiResponse<City> createCity(@RequestBody CityBody b) { return ApiResponse.ok(cities.save(apply(new City(), b))); }
    @PutMapping("/cities/{id}") public ApiResponse<City> updateCity(@PathVariable Long id, @RequestBody CityBody b) { return ApiResponse.ok(cities.save(apply(cities.findById(id).orElseThrow(() -> BusinessException.notFound("City")), b))); }
    private City apply(City c, CityBody b) { c.setName(b.name()); c.setState(b.state()); c.setCenterLat(b.centerLat()); c.setCenterLng(b.centerLng()); if (b.active() != null) c.setActive(b.active()); return c; }

    @GetMapping("/zones") public ApiResponse<List<Zone>> zones(@RequestParam(required = false) Long cityId) { return ApiResponse.ok(cityId == null ? zones.findAll() : zones.findByCityId(cityId)); }
    @PostMapping("/zones") public ApiResponse<Zone> createZone(@RequestBody ZoneBody b) { return ApiResponse.ok(zones.save(apply(new Zone(), b))); }
    @PutMapping("/zones/{id}") public ApiResponse<Zone> updateZone(@PathVariable Long id, @RequestBody ZoneBody b) { return ApiResponse.ok(zones.save(apply(zones.findById(id).orElseThrow(() -> BusinessException.notFound("Zone")), b))); }
    @DeleteMapping("/zones/{id}") public ApiResponse<Void> deactivateZone(@PathVariable Long id) { Zone z = zones.findById(id).orElseThrow(() -> BusinessException.notFound("Zone")); z.setActive(false); zones.save(z); return ApiResponse.ok(null); }
    private Zone apply(Zone z, ZoneBody b) {
        if (b.radiusKm() == null && (b.polygonCoords() == null || b.polygonCoords().isBlank())) throw BusinessException.bad("GEOFENCE_REQUIRED", "Provide radiusKm or polygonCoords");
        if (b.polygonCoords() != null && !b.polygonCoords().isBlank() && com.urbancompany.platform.common.GeoUtils.parsePolygon(b.polygonCoords()).size() < 3) throw BusinessException.bad("INVALID_POLYGON", "Polygon needs at least 3 vertices");
        z.setCityId(b.cityId()); z.setName(b.name()); z.setCenterLat(b.centerLat()); z.setCenterLng(b.centerLng()); z.setRadiusKm(b.radiusKm()); z.setPolygonCoords(b.polygonCoords());
        if (b.active() != null) z.setActive(b.active());
        return z;
    }
}
