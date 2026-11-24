package com.urbancompany.platform.cart;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Mutable accumulator passed through the pricing strategy chain. */
@Getter @Setter
public class PriceBreakdown {
    private BigDecimal subtotal = BigDecimal.ZERO;
    private BigDecimal surgeAmount = BigDecimal.ZERO;
    private BigDecimal membershipDiscount = BigDecimal.ZERO;
    private BigDecimal couponDiscount = BigDecimal.ZERO;
    private BigDecimal convenienceFee = BigDecimal.ZERO;
    private BigDecimal tax = BigDecimal.ZERO;
    private BigDecimal total = BigDecimal.ZERO;
    private boolean convenienceFeeWaived;
    private String couponCode;
    private List<String> notes = new ArrayList<>();

    public BigDecimal taxableBase() {
        return subtotal.add(surgeAmount).subtract(membershipDiscount).subtract(couponDiscount).add(convenienceFee);
    }
}
