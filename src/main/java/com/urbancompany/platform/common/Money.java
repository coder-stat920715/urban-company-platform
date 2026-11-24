package com.urbancompany.platform.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private Money() {}
    public static BigDecimal r(BigDecimal v) { return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP); }
    public static BigDecimal pct(BigDecimal base, BigDecimal percent) { return r(base.multiply(percent).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)); }
    public static BigDecimal min(BigDecimal a, BigDecimal b) { return a.compareTo(b) <= 0 ? a : b; }
    public static BigDecimal max(BigDecimal a, BigDecimal b) { return a.compareTo(b) >= 0 ? a : b; }
}
