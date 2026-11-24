package com.urbancompany.platform.partner;

import com.urbancompany.platform.auth.SecurityUtils;
import com.urbancompany.platform.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/partner")
@RequiredArgsConstructor
public class PartnerController {
    private final PartnerService service;
    private final EarningsService earnings;
    private final PartnerTrackingService tracking;

    public record DocRequest(@NotBlank String docType, String docNumber, @NotBlank String fileUrl) {}
    public record OnlineRequest(boolean online, Double lat, Double lng) {}
    public record LeaveRequest(@NotNull Instant from, @NotNull Instant to, String reason) {}
    public record WithdrawRequest(@NotNull @Positive BigDecimal amount) {}

    @PostMapping("/profile") public ApiResponse<Partner> onboard(@RequestBody PartnerService.ProfileRequest r) { return ApiResponse.ok(service.onboard(SecurityUtils.userId(), r)); }
    @PutMapping("/profile") public ApiResponse<Partner> update(@RequestBody PartnerService.ProfileRequest r) { return ApiResponse.ok(service.updateProfile(SecurityUtils.userId(), r)); }
    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile() {
        Partner p = service.byUser(SecurityUtils.userId());
        return ApiResponse.ok(Map.of("profile", p, "skills", service.skills(p.getId()), "documents", service.documents(p.getId())));
    }

    @PostMapping("/documents") public ApiResponse<PartnerDocument> addDoc(@Valid @RequestBody DocRequest r) { return ApiResponse.ok(service.addDocument(SecurityUtils.userId(), r.docType(), r.docNumber(), r.fileUrl())); }
    @PostMapping("/kyc/submit") public ApiResponse<Partner> submit() { return ApiResponse.ok(service.submitForReview(SecurityUtils.userId())); }

    @PutMapping("/availability") public ApiResponse<Partner> online(@RequestBody OnlineRequest r) { return ApiResponse.ok(service.setOnline(SecurityUtils.userId(), r.online(), r.lat(), r.lng())); }
    /** REST fallback for the 5-second GPS heartbeat (STOMP: /app/partner/heartbeat). */
    @PostMapping("/location") public ApiResponse<Void> location(@RequestBody PartnerTrackingService.Heartbeat hb) { tracking.heartbeat(SecurityUtils.userId(), hb); return ApiResponse.ok(null); }

    @PutMapping("/working-hours") public ApiResponse<Void> hours(@RequestBody List<PartnerService.WorkingHour> hours) { service.replaceWorkingHours(SecurityUtils.userId(), hours); return ApiResponse.ok(null); }
    @GetMapping("/working-hours") public ApiResponse<List<Map<String, Object>>> getHours() { return ApiResponse.ok(service.workingHours(SecurityUtils.userId())); }
    @PostMapping("/leaves") public ApiResponse<Void> addLeave(@Valid @RequestBody LeaveRequest r) { service.addLeave(SecurityUtils.userId(), r.from(), r.to(), r.reason()); return ApiResponse.ok(null); }
    @GetMapping("/leaves") public ApiResponse<List<Map<String, Object>>> leaves() { return ApiResponse.ok(service.leaves(SecurityUtils.userId())); }
    @DeleteMapping("/leaves/{id}") public ApiResponse<Void> deleteLeave(@PathVariable Long id) { service.deleteLeave(SecurityUtils.userId(), id); return ApiResponse.ok(null); }

    @GetMapping("/earnings/summary") public ApiResponse<Map<String, Object>> summary() { return ApiResponse.ok(earnings.summary(service.byUser(SecurityUtils.userId()).getId())); }
    @GetMapping("/earnings/history")
    public ApiResponse<List<Map<String, Object>>> history(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(earnings.history(service.byUser(SecurityUtils.userId()).getId(), page, Math.min(size, 100)));
    }
    @GetMapping("/earnings/tds-summary") public ApiResponse<List<Map<String, Object>>> tds() { return ApiResponse.ok(earnings.tdsSummary(service.byUser(SecurityUtils.userId()).getId())); }
    @PostMapping("/withdrawals") public ApiResponse<Map<String, Object>> withdraw(@Valid @RequestBody WithdrawRequest r) { return ApiResponse.ok(earnings.requestWithdrawal(SecurityUtils.userId(), r.amount())); }
    @GetMapping("/withdrawals") public ApiResponse<List<Map<String, Object>>> withdrawals() { return ApiResponse.ok(earnings.withdrawals(service.byUser(SecurityUtils.userId()).getId())); }
}
