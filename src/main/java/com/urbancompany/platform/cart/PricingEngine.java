package com.urbancompany.platform.cart;

import com.urbancompany.platform.common.Money;
import org.springframework.stereotype.Service;

import java.util.List;

/** Runs all {@link PricingStrategy} beans in @Order: surge -> instant -> membership -> coupon -> convenience fee -> tax. */
@Service
public class PricingEngine {
    private final List<PricingStrategy> strategies;

    public PricingEngine(List<PricingStrategy> strategies) { this.strategies = strategies; }

    public PriceBreakdown price(PricingContext ctx) {
        PriceBreakdown b = new PriceBreakdown();
        b.setSubtotal(Money.r(ctx.subtotal()));
        for (PricingStrategy s : strategies) s.apply(ctx, b);
        return b;
    }
}
