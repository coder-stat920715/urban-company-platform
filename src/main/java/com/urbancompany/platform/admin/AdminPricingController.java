package com.urbancompany.platform.admin;

import com.urbancompany.platform.cart.*;
import com.urbancompany.platform.common.ApiResponse;
import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.partner.CommissionTier;
import com.urbancompany.platform.partner.CommissionTierRepository;
import com.urbancompany.platform.payment.MembershipPlan;
import com.urbancompany.platform.payment.MembershipPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Dynamic pricing, surge rules, commission tiers, promo coupons and UC Plus plans. */
@RestController
@RequestMapping("/api/v1/admin/pricing")
@RequiredArgsConstructor
public class AdminPricingController {
    private final SurgeRuleRepository surges;
    private final CouponRepository coupons;
    private final CommissionTierRepository tiers;
    private final MembershipPlanRepository plans;

    // ---- surge rules ----
    @GetMapping("/surge-rules") public ApiResponse<List<SurgeRule>> surges() { return ApiResponse.ok(surges.findAll()); }
    @PostMapping("/surge-rules") public ApiResponse<SurgeRule> createSurge(@RequestBody SurgeRule r) { r.setId(null); return ApiResponse.ok(surges.save(r)); }
    @PutMapping("/surge-rules/{id}") public ApiResponse<SurgeRule> updateSurge(@PathVariable Long id, @RequestBody SurgeRule r) { surges.findById(id).orElseThrow(() -> BusinessException.notFound("Surge rule")); r.setId(id); return ApiResponse.ok(surges.save(r)); }
    @DeleteMapping("/surge-rules/{id}") public ApiResponse<Void> deleteSurge(@PathVariable Long id) { surges.deleteById(id); return ApiResponse.ok(null); }

    // ---- coupons ----
    @GetMapping("/coupons") public ApiResponse<List<Coupon>> coupons() { return ApiResponse.ok(coupons.findAll()); }
    @PostMapping("/coupons") public ApiResponse<Coupon> createCoupon(@RequestBody Coupon c) { c.setId(null); c.setUsedCount(0); c.setCode(c.getCode().trim().toUpperCase()); return ApiResponse.ok(coupons.save(c)); }
    @PutMapping("/coupons/{id}")
    public ApiResponse<Coupon> updateCoupon(@PathVariable Long id, @RequestBody Coupon c) {
        Coupon old = coupons.findById(id).orElseThrow(() -> BusinessException.notFound("Coupon"));
        c.setId(id); c.setUsedCount(old.getUsedCount()); c.setCode(c.getCode().trim().toUpperCase());
        return ApiResponse.ok(coupons.save(c));
    }
    @DeleteMapping("/coupons/{id}") public ApiResponse<Void> deactivateCoupon(@PathVariable Long id) { Coupon c = coupons.findById(id).orElseThrow(() -> BusinessException.notFound("Coupon")); c.setActive(false); coupons.save(c); return ApiResponse.ok(null); }

    // ---- commission tiers ----
    @GetMapping("/commission-tiers") public ApiResponse<List<CommissionTier>> tiers() { return ApiResponse.ok(tiers.findAllByOrderByMinJobsDesc()); }
    @PostMapping("/commission-tiers") public ApiResponse<CommissionTier> createTier(@RequestBody CommissionTier t) { t.setId(null); return ApiResponse.ok(tiers.save(t)); }
    @PutMapping("/commission-tiers/{id}") public ApiResponse<CommissionTier> updateTier(@PathVariable Long id, @RequestBody CommissionTier t) { tiers.findById(id).orElseThrow(() -> BusinessException.notFound("Tier")); t.setId(id); return ApiResponse.ok(tiers.save(t)); }
    @DeleteMapping("/commission-tiers/{id}") public ApiResponse<Void> deleteTier(@PathVariable Long id) { tiers.deleteById(id); return ApiResponse.ok(null); }

    // ---- UC Plus plans ----
    @GetMapping("/membership-plans") public ApiResponse<List<MembershipPlan>> plans() { return ApiResponse.ok(plans.findAll()); }
    @PostMapping("/membership-plans") public ApiResponse<MembershipPlan> createPlan(@RequestBody MembershipPlan p) { p.setId(null); return ApiResponse.ok(plans.save(p)); }
    @PutMapping("/membership-plans/{id}") public ApiResponse<MembershipPlan> updatePlan(@PathVariable Long id, @RequestBody MembershipPlan p) { plans.findById(id).orElseThrow(() -> BusinessException.notFound("Plan")); p.setId(id); return ApiResponse.ok(plans.save(p)); }
}
