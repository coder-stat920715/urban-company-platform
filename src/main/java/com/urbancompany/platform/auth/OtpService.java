package com.urbancompany.platform.auth;

import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/** Phone OTP stored in Redis with TTL, hourly rate-limit and attempt lockout. SMS provider is a log stub. */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {
    private static final SecureRandom RNG = new SecureRandom();
    private final StringRedisTemplate redis;
    private final AppProperties props;

    /** @return the OTP when app.otp.expose=true (dev), otherwise null. */
    public String send(String phone) {
        String rateKey = "otp:rate:" + phone;
        Long n = redis.opsForValue().increment(rateKey);
        if (n != null && n == 1) redis.expire(rateKey, Duration.ofHours(1));
        if (n != null && n > props.otp().maxRequestsPerHour()) throw new BusinessException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMIT", "Too many OTP requests, try later");
        String otp = String.format("%06d", RNG.nextInt(1_000_000));
        redis.opsForValue().set("otp:code:" + phone, otp, Duration.ofSeconds(props.otp().ttlSeconds()));
        redis.delete("otp:attempts:" + phone);
        log.info("[SMS-STUB] OTP for {} is {}", phone, props.otp().expose() ? otp : "******");
        return props.otp().expose() ? otp : null;
    }

    public void verify(String phone, String otp) {
        String attemptsKey = "otp:attempts:" + phone;
        Long attempts = redis.opsForValue().increment(attemptsKey);
        if (attempts != null && attempts == 1) redis.expire(attemptsKey, Duration.ofSeconds(props.otp().ttlSeconds()));
        if (attempts != null && attempts > 5) throw BusinessException.bad("OTP_LOCKED", "Too many wrong attempts, request a new OTP");
        String stored = redis.opsForValue().get("otp:code:" + phone);
        if (stored == null || !stored.equals(otp)) throw BusinessException.bad("OTP_INVALID", "Invalid or expired OTP");
        redis.delete("otp:code:" + phone);
        redis.delete(attemptsKey);
    }
}
