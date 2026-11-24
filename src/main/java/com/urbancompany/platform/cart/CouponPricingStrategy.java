package com.urbancompany.platform.cart;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component @Order(30)
@RequiredArgsConstructor
public class CouponPricingStrategy implements PricingStrategy {
    private final CouponService coupons;

    @Override
    public void apply(PricingContext ctx, PriceBreakdown b) {
        if (ctx.couponCode() == null || ctx.couponCode().isBlank()) return;
        BigDecimal payableBase = b.getSubtotal().add(b.getSurgeAmount()).subtract(b.getMembershipDiscount());
        BigDecimal d = coupons.discountFor(ctx.couponCode(), ctx.userId(), b.getSubtotal(), payableBase);
        b.setCouponDiscount(d);
        b.setCouponCode(ctx.couponCode().trim().toUpperCase());
        b.getNotes().add("Coupon " + b.getCouponCode() + " applied");
    }
}
