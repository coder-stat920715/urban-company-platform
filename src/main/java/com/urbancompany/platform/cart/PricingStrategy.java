package com.urbancompany.platform.cart;

/** Strategy pattern: each rule adjusts the running breakdown. Execution order is defined by @Order on the implementations. */
public interface PricingStrategy {
    void apply(PricingContext ctx, PriceBreakdown breakdown);
}
