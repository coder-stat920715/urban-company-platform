package com.urbancompany.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String timezone, int warrantyDays, boolean seedDemo, Jwt jwt, Otp otp, Admin admin,
                            Pricing pricing, Slots slots, Dispatch dispatch, Payment payment, Earnings earnings) {
    public record Jwt(String secret, int accessTtlMinutes, int refreshTtlDays) {}
    public record Otp(boolean expose, int ttlSeconds, int maxRequestsPerHour) {}
    public record Admin(String email, String password, String phone) {}
    public record Pricing(BigDecimal convenienceFee, BigDecimal instantSurcharge, BigDecimal gstPercent, BigDecimal plusCashbackPercent) {}
    public record Slots(LocalTime dayStart, LocalTime dayEnd, int stepMinutes, int bufferMinutes, int minLeadMinutes, int horizonDays, int paymentHoldMinutes) {}
    public record Dispatch(int offerTtlSeconds, List<Integer> radiiKm, int searchWindowMinutes, int retrySeconds, int leadMinutes,
                           int maxConcurrentJobs, double weightDistance, double weightRating, double weightAcceptance, double weightWorkload) {}
    public record Payment(String webhookSecret) {}
    public record Earnings(BigDecimal tdsPercent, BigDecimal minWithdrawal) {}
}
