package com.urbancompany.platform.cart;

import com.urbancompany.platform.common.Money;
import com.urbancompany.platform.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Insta-Service rapid-dispatch surcharge (added into the surge bucket). */
@Component @Order(15)
@RequiredArgsConstructor
public class InstantSurchargeStrategy implements PricingStrategy {
    private final AppProperties props;

    @Override
    public void apply(PricingContext ctx, PriceBreakdown b) {
        if (!ctx.instant()) return;
        b.setSurgeAmount(Money.r(b.getSurgeAmount().add(props.pricing().instantSurcharge())));
        b.getNotes().add("Insta-Service surcharge");
    }
}
