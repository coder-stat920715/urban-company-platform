package com.urbancompany.platform.admin;

import com.urbancompany.platform.auth.SecurityUtils;
import com.urbancompany.platform.booking.BookingService;
import com.urbancompany.platform.common.ApiResponse;
import com.urbancompany.platform.dispatch.DispatchService;
import com.urbancompany.platform.payment.WalletService;
import com.urbancompany.platform.review.WarrantyClaim;
import com.urbancompany.platform.review.WarrantyService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Live booking operations: reassignment, cancellation penalty overrides, refunds, dispute and warranty resolution. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminBookingController {
    private final BookingService bookings;
    private final DispatchService dispatch;
    private final WarrantyService warranty;
    private final WalletService wallets;

    public record ReassignBody(Long partnerId, @NotBlank String reason) {}
    public record CancelBody(@NotBlank String reason, BigDecimal cancellationFee) {}
    public record RefundBody(@NotNull BigDecimal amount, @NotBlank String reason) {}
    public record DisputeBody(BigDecimal refundAmount, @NotBlank String note) {}
    public record WarrantyDecision(@NotBlank String decision, String note, BigDecimal refundAmount) {}
    public record WalletCredit(@NotNull Long userId, @NotNull BigDecimal amount, @NotBlank String reason) {}

    @GetMapping("/bookings")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(bookings.adminList(status, page, Math.min(size, 100)));
    }
    @GetMapping("/bookings/{id}") public ApiResponse<Map<String, Object>> get(@PathVariable Long id) { return ApiResponse.ok(bookings.toView(bookings.get(id), true)); }

    /** partnerId omitted => re-run automatic dispatch; present => manual assignment. */
    @PostMapping("/bookings/{id}/reassign")
    public ApiResponse<Map<String, Object>> reassign(@PathVariable Long id, @Valid @RequestBody ReassignBody b) {
        dispatch.adminReassign(id, SecurityUtils.userId(), b.partnerId(), b.reason());
        return ApiResponse.ok(bookings.toView(bookings.get(id), true));
    }

    /** cancellationFee overrides the default penalty policy (0 = waive). */
    @PostMapping("/bookings/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id, @Valid @RequestBody CancelBody b) {
        bookings.cancelByAdmin(SecurityUtils.userId(), id, b.reason(), b.cancellationFee());
        return ApiResponse.ok(bookings.toView(bookings.get(id), true));
    }

    @PostMapping("/bookings/{id}/refund")
    public ApiResponse<Map<String, Object>> refund(@PathVariable Long id, @Valid @RequestBody RefundBody b) {
        bookings.refundOnly(SecurityUtils.userId(), id, b.amount(), b.reason());
        return ApiResponse.ok(bookings.toView(bookings.get(id), true));
    }

    @PostMapping("/bookings/{id}/resolve-dispute")
    public ApiResponse<Map<String, Object>> resolve(@PathVariable Long id, @Valid @RequestBody DisputeBody b) {
        bookings.resolveDispute(SecurityUtils.userId(), id, b.refundAmount(), b.note());
        return ApiResponse.ok(bookings.toView(bookings.get(id), true));
    }

    @GetMapping("/warranty-claims") public ApiResponse<List<WarrantyClaim>> claims(@RequestParam(required = false) String status) { return ApiResponse.ok(warranty.byStatus(status)); }

    @PostMapping("/warranty-claims/{id}/resolve")
    public ApiResponse<WarrantyClaim> resolveClaim(@PathVariable Long id, @Valid @RequestBody WarrantyDecision d) {
        return ApiResponse.ok(warranty.resolve(SecurityUtils.userId(), id, d.decision(), d.note(), d.refundAmount()));
    }

    @PostMapping("/wallet-credit")
    public ApiResponse<Map<String, Object>> credit(@Valid @RequestBody WalletCredit c) {
        return ApiResponse.ok(Map.of("balance", wallets.credit(c.userId(), c.amount(), "ADMIN_ADJUSTMENT", "admin:" + SecurityUtils.userId(), c.reason())));
    }
}
