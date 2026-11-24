package com.urbancompany.platform.cart;

import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.common.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class CouponService {
    private final CouponRepository coupons;
    private final JdbcClient jdbc;

    /** Validates the coupon for this user/order and returns the discount (never more than the payable base). */
    public BigDecimal discountFor(String code, Long userId, BigDecimal orderValue, BigDecimal payableBase) {
        Coupon c = coupons.findByCodeIgnoreCase(code.trim()).orElseThrow(() -> BusinessException.unprocessable("COUPON_INVALID", "Coupon not found"));
        Instant now = Instant.now();
        if (!c.isActive()) throw BusinessException.unprocessable("COUPON_INVALID", "Coupon is not active");
        if (c.getValidFrom() != null && now.isBefore(c.getValidFrom())) throw BusinessException.unprocessable("COUPON_INVALID", "Coupon is not valid yet");
        if (c.getValidTo() != null && now.isAfter(c.getValidTo())) throw BusinessException.unprocessable("COUPON_EXPIRED", "Coupon has expired");
        if (orderValue.compareTo(c.getMinOrder()) < 0) throw BusinessException.unprocessable("COUPON_MIN_ORDER", "Minimum order value for this coupon is " + c.getMinOrder());
        if (c.getUsageLimit() != null && c.getUsedCount() >= c.getUsageLimit()) throw BusinessException.unprocessable("COUPON_EXHAUSTED", "Coupon usage limit reached");
        Integer used = jdbc.sql("SELECT count(*) FROM coupon_redemptions WHERE coupon_id = ? AND user_id = ?").param(c.getId()).param(userId).query(Integer.class).single();
        if (used >= c.getPerUserLimit()) throw BusinessException.unprocessable("COUPON_ALREADY_USED", "You have already used this coupon");
        BigDecimal d = "PERCENT".equalsIgnoreCase(c.getDiscountType()) ? Money.pct(orderValue, c.getDiscountValue()) : Money.r(c.getDiscountValue());
        if (c.getMaxDiscount() != null) d = Money.min(d, c.getMaxDiscount());
        return Money.min(d, payableBase);
    }

    /** Atomically consumes one use (conditional UPDATE guards the global limit under concurrency). */
    @Transactional
    public void redeem(String code, Long userId, Long bookingId) {
        Coupon c = coupons.findByCodeIgnoreCase(code).orElse(null);
        if (c == null) return;
        int n = jdbc.sql("UPDATE coupons SET used_count = used_count + 1 WHERE id = ? AND (usage_limit IS NULL OR used_count < usage_limit)").param(c.getId()).update();
        if (n == 1) jdbc.sql("INSERT INTO coupon_redemptions(coupon_id, user_id, booking_id) VALUES (?,?,?)").param(c.getId()).param(userId).param(bookingId).update();
    }
}
