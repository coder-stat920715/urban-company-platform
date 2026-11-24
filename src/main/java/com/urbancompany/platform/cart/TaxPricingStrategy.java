package com.urbancompany.platform.cart;

import com.urbancompany.platform.common.Money;
import com.urbancompany.platform.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component @Order(50)
@RequiredArgsConstructor
public class TaxPricingStrategy implements PricingStrategy {
    private final AppProperties props;

    @Override
    public void apply(PricingContext ctx, PriceBreakdown b) {
        BigDecimal base = Money.max(BigDecimal.ZERO, b.taxableBase());
        b.setTax(Money.pct(base, props.pricing().gstPercent()));
        b.setTotal(Money.r(base.add(b.getTax())));
    }
}
