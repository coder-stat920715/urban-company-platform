package com.urbancompany.platform.catalog;

import com.urbancompany.platform.catalog.CatalogViews.*;
import com.urbancompany.platform.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog")
@RequiredArgsConstructor
public class CatalogController {
    private final CatalogService catalog;

    @GetMapping("/categories")
    public ApiResponse<List<CategoryNode>> tree() { return ApiResponse.ok(catalog.tree()); }

    @GetMapping("/categories/{id}/services")
    public ApiResponse<List<ServiceSummary>> services(@PathVariable Long id) { return ApiResponse.ok(catalog.servicesByCategory(id)); }

    @GetMapping("/services/{id}")
    public ApiResponse<ServiceDetail> service(@PathVariable Long id) { return ApiResponse.ok(catalog.serviceDetail(id)); }

    @GetMapping("/packages/{id}")
    public ApiResponse<PackageDetail> pack(@PathVariable Long id) { return ApiResponse.ok(catalog.packageDetail(id)); }
}
