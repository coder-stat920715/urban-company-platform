package com.urbancompany.platform.cart;

import com.urbancompany.platform.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component @Order(40)
@RequiredArgsConstructor
public class ConvenienceFeeStrategy implements PricingStrategy {
    private final AppProperties props;

    @Override
    public void apply(PricingContext ctx, PriceBreakdown b) {
        if (b.isConvenienceFeeWaived()) { b.setConvenienceFee(BigDecimal.ZERO); b.getNotes().add("Convenience fee waived (UC Plus)"); }
        else b.setConvenienceFee(props.pricing().convenienceFee());
    }
}
