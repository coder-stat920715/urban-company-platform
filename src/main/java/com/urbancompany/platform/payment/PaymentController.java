package com.urbancompany.platform.payment;

import com.urbancompany.platform.auth.SecurityUtils;
import com.urbancompany.platform.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService payments;
    private final WalletService wallets;
    private final MembershipService memberships;

    public record BookingIntentRequest(@NotNull Long bookingId, String method, boolean useWallet) {}
    public record MembershipIntentRequest(@NotNull Long planId, String method, boolean useWallet) {}

    @PostMapping("/payments/intents")
    public ApiResponse<PaymentService.Intent> intent(@Valid @RequestBody BookingIntentRequest r) {
        return ApiResponse.ok(payments.createBookingIntent(SecurityUtils.userId(), r.bookingId(), r.method(), r.useWallet()));
    }

    /** Gateway webhook (HMAC-SHA256 over raw body in X-UC-Signature). */
    @PostMapping("/payments/webhook")
    public ApiResponse<Void> webhook(@RequestBody String raw, @RequestHeader(value = "X-UC-Signature", required = false) String signature) {
        payments.handleWebhook(raw, signature);
        return ApiResponse.ok(null);
    }

    /** Demo helper: plays the role of the gateway and calls our own webhook handler with a correctly signed event. */
    @PostMapping("/payments/simulate/{intentId}")
    public ApiResponse<Map<String, Object>> simulate(@PathVariable String intentId, @RequestParam(defaultValue = "SUCCESS") String outcome) {
        Payment p = payments.byIntent(intentId);
        if (!p.getUserId().equals(SecurityUtils.userId())) throw com.urbancompany.platform.common.BusinessException.forbidden("Not your payment");
        String body = payments.buildWebhookBody(intentId, !"FAILURE".equalsIgnoreCase(outcome));
        payments.handleWebhook(body, payments.sign(body));
        return ApiResponse.ok(Map.of("intentId", intentId, "status", payments.byIntent(intentId).getStatus()));
    }

    // ---- wallet ----
    @GetMapping("/wallet")
    public ApiResponse<Map<String, Object>> wallet() { return ApiResponse.ok(Map.of("balance", wallets.balance(SecurityUtils.userId()))); }

    @GetMapping("/wallet/transactions")
    public ApiResponse<List<Map<String, Object>>> txns(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(wallets.transactions(SecurityUtils.userId(), page, Math.min(size, 100)));
    }

    // ---- UC Plus ----
    @GetMapping("/membership/plans")
    public ApiResponse<List<MembershipPlan>> plans() { return ApiResponse.ok(memberships.plans()); }

    @GetMapping("/membership")
    public ApiResponse<Map<String, Object>> mine() {
        return ApiResponse.ok(memberships.activeMembership(SecurityUtils.userId())
                .<Map<String, Object>>map(m -> Map.of("active", true, "membership", m, "plan", memberships.plan(m.getPlanId())))
                .orElse(Map.of("active", false)));
    }

    @PostMapping("/membership/subscribe")
    public ApiResponse<PaymentService.Intent> subscribe(@Valid @RequestBody MembershipIntentRequest r) {
        return ApiResponse.ok(payments.createMembershipIntent(SecurityUtils.userId(), r.planId(), r.method(), r.useWallet()));
    }
}
