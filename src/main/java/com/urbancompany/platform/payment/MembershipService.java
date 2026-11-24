package com.urbancompany.platform.payment;

import com.urbancompany.platform.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** UC Plus: flat % discount + zero convenience fee while an ACTIVE membership exists. */
@Service
@RequiredArgsConstructor
public class MembershipService {
    private final MembershipRepository memberships;
    private final MembershipPlanRepository plans;

    public List<MembershipPlan> plans() { return plans.findByActiveTrue(); }
    public MembershipPlan plan(Long id) { return plans.findById(id).filter(MembershipPlan::isActive).orElseThrow(() -> BusinessException.notFound("Plan")); }

    public Optional<MembershipPlan> activePlan(Long userId) {
        return memberships.findFirstByUserIdAndStatusAndEndAtAfterOrderByEndAtDesc(userId, "ACTIVE", Instant.now()).flatMap(m -> plans.findById(m.getPlanId()));
    }

    public Optional<Membership> activeMembership(Long userId) {
        return memberships.findFirstByUserIdAndStatusAndEndAtAfterOrderByEndAtDesc(userId, "ACTIVE", Instant.now());
    }

    @Transactional
    public Membership activate(Long userId, Long planId) {
        MembershipPlan plan = plan(planId);
        Instant start = activeMembership(userId).map(Membership::getEndAt).orElse(Instant.now());   // renewals stack
        Membership m = new Membership();
        m.setUserId(userId); m.setPlanId(planId); m.setStartAt(start); m.setEndAt(start.plus(Duration.ofDays(plan.getDurationDays())));
        return memberships.save(m);
    }
}
