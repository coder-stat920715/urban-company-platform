package com.urbancompany.platform.dispatch;

import com.urbancompany.platform.auth.SecurityUtils;
import com.urbancompany.platform.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/partner/offers")
@RequiredArgsConstructor
public class PartnerOfferController {
    private final DispatchService dispatch;

    @GetMapping public ApiResponse<List<Map<String, Object>>> pending() { return ApiResponse.ok(dispatch.pendingOffers(SecurityUtils.userId())); }

    /** Atomic accept: only one partner can ever win a booking (Redisson lock + state check + @Version). */
    @PostMapping("/{id}/accept") public ApiResponse<Map<String, Object>> accept(@PathVariable Long id) { return ApiResponse.ok(dispatch.accept(id, SecurityUtils.userId())); }

    @PostMapping("/{id}/reject") public ApiResponse<Void> reject(@PathVariable Long id) { dispatch.reject(id, SecurityUtils.userId()); return ApiResponse.ok(null); }
}
