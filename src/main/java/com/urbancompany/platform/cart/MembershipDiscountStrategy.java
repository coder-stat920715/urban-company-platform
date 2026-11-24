package com.urbancompany.platform.cart;

import com.urbancompany.platform.common.Money;
import com.urbancompany.platform.payment.MembershipPlan;
import com.urbancompany.platform.payment.MembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** UC Plus: flat percentage off the service subtotal and zero convenience fee. */
@Component @Order(20)
@RequiredArgsConstructor
public class MembershipDiscountStrategy implements PricingStrategy {
    private final MembershipService memberships;

    @Override
    public void apply(PricingContext ctx, PriceBreakdown b) {
        Optional<MembershipPlan> plan = memberships.activePlan(ctx.userId());
        if (plan.isEmpty()) return;
        b.setMembershipDiscount(Money.pct(b.getSubtotal(), plan.get().getDiscountPercent()));
        b.setConvenienceFeeWaived(plan.get().isZeroConvenienceFee());
        b.getNotes().add(plan.get().getName() + " discount " + plan.get().getDiscountPercent() + "%");
    }
}
