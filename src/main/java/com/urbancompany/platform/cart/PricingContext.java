package com.urbancompany.platform.cart;

import java.math.BigDecimal;
import java.time.Instant;

public record PricingContext(Long userId, Long zoneId, Instant scheduledStart, boolean instant, BigDecimal subtotal, String couponCode) {}
