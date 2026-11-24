package com.urbancompany.platform.booking;

import com.urbancompany.platform.auth.SecurityUtils;
import com.urbancompany.platform.common.ApiResponse;
import com.urbancompany.platform.dispatch.DispatchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Partner-side job execution: EN_ROUTE -> ARRIVED (+start OTP) -> IN_PROGRESS -> (extra work) -> COMPLETED (end OTP). */
@RestController
@RequestMapping("/api/v1/partner/jobs")
@RequiredArgsConstructor
public class PartnerJobController {
    private final BookingService bookings;
    private final DispatchService dispatch;

    public record OtpBody(@NotBlank String otp) {}
    public record ExtraBody(@NotBlank String description, @NotNull @Positive BigDecimal amount, int extraMinutes) {}
    public record ReleaseBody(String reason) {}

    @GetMapping public ApiResponse<List<Map<String, Object>>> jobs(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(bookings.partnerJobs(SecurityUtils.userId(), page, Math.min(size, 100)));
    }
    @GetMapping("/{id}") public ApiResponse<Map<String, Object>> job(@PathVariable Long id) { return ApiResponse.ok(bookings.partnerView(SecurityUtils.userId(), id)); }

    @PostMapping("/{id}/en-route") public ApiResponse<Map<String, Object>> enRoute(@PathVariable Long id) { bookings.enRoute(SecurityUtils.userId(), id); return ApiResponse.ok(bookings.partnerView(SecurityUtils.userId(), id)); }
    @PostMapping("/{id}/arrive") public ApiResponse<Map<String, Object>> arrive(@PathVariable Long id) { bookings.arrive(SecurityUtils.userId(), id); return ApiResponse.ok(bookings.partnerView(SecurityUtils.userId(), id)); }
    @PostMapping("/{id}/verify-start-otp") public ApiResponse<Map<String, Object>> verify(@PathVariable Long id, @Valid @RequestBody OtpBody b) { bookings.verifyStartOtp(SecurityUtils.userId(), id, b.otp()); return ApiResponse.ok(bookings.partnerView(SecurityUtils.userId(), id)); }
    @PostMapping("/{id}/start") public ApiResponse<Map<String, Object>> start(@PathVariable Long id) { bookings.startWork(SecurityUtils.userId(), id); return ApiResponse.ok(bookings.partnerView(SecurityUtils.userId(), id)); }
    @PostMapping("/{id}/extra-work") public ApiResponse<ExtraWorkRequest> extra(@PathVariable Long id, @Valid @RequestBody ExtraBody b) { return ApiResponse.ok(bookings.requestExtraWork(SecurityUtils.userId(), id, b.description(), b.amount(), b.extraMinutes())); }
    @PostMapping("/{id}/complete") public ApiResponse<Map<String, Object>> complete(@PathVariable Long id, @Valid @RequestBody OtpBody b) { bookings.complete(SecurityUtils.userId(), id, b.otp()); return ApiResponse.ok(bookings.partnerView(SecurityUtils.userId(), id)); }

    /** Partner drops an assigned job (before starting): it is re-offered to other partners. */
    @PostMapping("/{id}/release") public ApiResponse<Void> release(@PathVariable Long id, @RequestBody(required = false) ReleaseBody b) {
        dispatch.releaseByPartner(id, SecurityUtils.userId(), b == null ? null : b.reason());
        return ApiResponse.ok(null);
    }
}
