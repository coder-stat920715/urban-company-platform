package com.urbancompany.platform.admin;

import com.urbancompany.platform.auth.SecurityUtils;
import com.urbancompany.platform.common.ApiResponse;
import com.urbancompany.platform.partner.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/partners")
@RequiredArgsConstructor
public class AdminPartnerController {
    private final PartnerService partners;
    private final PartnerRepository repo;
    private final EarningsService earnings;

    public record ReviewBody(boolean approve, String remarks) {}
    public record ReasonBody(String reason) {}
    public record ProcessBody(boolean approve, String remarks) {}

    @GetMapping
    public ApiResponse<List<Partner>> list(@RequestParam(defaultValue = "UNDER_REVIEW") PartnerStatus status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(repo.findByStatus(status, PageRequest.of(page, Math.min(size, 100))).getContent());
    }
    @GetMapping("/{id}/scorecard") public ApiResponse<Map<String, Object>> scorecard(@PathVariable Long id) { return ApiResponse.ok(partners.scorecard(id)); }
    @GetMapping("/{id}/documents") public ApiResponse<List<PartnerDocument>> docs(@PathVariable Long id) { return ApiResponse.ok(partners.documents(id)); }
    @GetMapping("/{id}/earnings") public ApiResponse<Map<String, Object>> earnings(@PathVariable Long id) { return ApiResponse.ok(earnings.summary(id)); }

    @PostMapping("/documents/{docId}/review")
    public ApiResponse<PartnerDocument> review(@PathVariable Long docId, @RequestBody ReviewBody b) { return ApiResponse.ok(partners.reviewDocument(SecurityUtils.userId(), docId, b.approve(), b.remarks())); }
    @PostMapping("/{id}/approve") public ApiResponse<Partner> approve(@PathVariable Long id) { return ApiResponse.ok(partners.approve(id)); }
    @PostMapping("/{id}/reject") public ApiResponse<Partner> reject(@PathVariable Long id, @RequestBody ReasonBody b) { return ApiResponse.ok(partners.reject(id, b.reason())); }
    @PostMapping("/{id}/suspend") public ApiResponse<Partner> suspend(@PathVariable Long id, @RequestBody ReasonBody b) { return ApiResponse.ok(partners.suspend(id, b.reason())); }
    @PostMapping("/{id}/reinstate") public ApiResponse<Partner> reinstate(@PathVariable Long id) { return ApiResponse.ok(partners.reinstate(id)); }

    @GetMapping("/withdrawals") public ApiResponse<List<Map<String, Object>>> withdrawals() { return ApiResponse.ok(earnings.pendingWithdrawals()); }
    @PostMapping("/withdrawals/{id}/process")
    public ApiResponse<Void> process(@PathVariable Long id, @RequestBody ProcessBody b) { earnings.processWithdrawal(id, b.approve(), b.remarks()); return ApiResponse.ok(null); }
}
