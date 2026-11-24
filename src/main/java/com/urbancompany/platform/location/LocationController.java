package com.urbancompany.platform.location;

import com.urbancompany.platform.auth.SecurityUtils;
import com.urbancompany.platform.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class LocationController {
    private final AddressService addresses;
    private final ZoneService zones;
    private final CityRepository cities;

    public record AddressBody(@NotBlank String label, @NotBlank String line1, String line2, String landmark, double lat, double lng, boolean makeDefault) {
        AddressService.AddressRequest toReq() { return new AddressService.AddressRequest(label, line1, line2, landmark, lat, lng, makeDefault); }
    }

    // ---- public geofence lookups ----
    @GetMapping("/api/v1/locations/cities")
    public ApiResponse<List<City>> cities() { return ApiResponse.ok(cities.findByActiveTrueOrderByName()); }

    @GetMapping("/api/v1/locations/serviceability")
    public ApiResponse<Map<String, Object>> serviceability(@RequestParam double lat, @RequestParam double lng) {
        return ApiResponse.ok(zones.resolve(lat, lng)
                .<Map<String, Object>>map(z -> Map.of("serviceable", true, "zoneId", z.getId(), "zoneName", z.getName(), "cityId", z.getCityId()))
                .orElse(Map.of("serviceable", false)));
    }

    // ---- saved addresses ----
    @GetMapping("/api/v1/addresses")
    public ApiResponse<List<Address>> list() { return ApiResponse.ok(addresses.list(SecurityUtils.userId())); }

    @PostMapping("/api/v1/addresses")
    public ApiResponse<Address> create(@Valid @RequestBody AddressBody b) { return ApiResponse.ok(addresses.create(SecurityUtils.userId(), b.toReq())); }

    @PutMapping("/api/v1/addresses/{id}")
    public ApiResponse<Address> update(@PathVariable Long id, @Valid @RequestBody AddressBody b) { return ApiResponse.ok(addresses.update(SecurityUtils.userId(), id, b.toReq())); }

    @DeleteMapping("/api/v1/addresses/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) { addresses.delete(SecurityUtils.userId(), id); return ApiResponse.ok(null); }
}
