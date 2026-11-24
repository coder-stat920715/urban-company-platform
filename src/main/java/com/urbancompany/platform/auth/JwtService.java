package com.urbancompany.platform.auth;

import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private final AppProperties props;
    private final StringRedisTemplate redis;
    private final SecretKey key;

    public JwtService(AppProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {}

    public TokenPair issue(User u) {
        Instant now = Instant.now();
        long accessTtl = Duration.ofMinutes(props.jwt().accessTtlMinutes()).toSeconds();
        String access = Jwts.builder().subject(String.valueOf(u.getId())).claim("role", u.getRole().name()).claim("typ", "access")
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(accessTtl))).signWith(key).compact();
        String jti = UUID.randomUUID().toString();
        Duration refreshTtl = Duration.ofDays(props.jwt().refreshTtlDays());
        String refresh = Jwts.builder().id(jti).subject(String.valueOf(u.getId())).claim("typ", "refresh")
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(refreshTtl))).signWith(key).compact();
        redis.opsForValue().set("auth:refresh:" + jti, String.valueOf(u.getId()), refreshTtl);
        return new TokenPair(access, refresh, accessTtl);
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /** Validates the refresh token and revokes it (rotation). Returns the user id. */
    public Long consumeRefresh(String token) {
        try {
            Claims c = parse(token);
            if (!"refresh".equals(c.get("typ"))) throw BusinessException.unauthorized("Not a refresh token");
            String stored = redis.opsForValue().getAndDelete("auth:refresh:" + c.getId());
            if (stored == null) throw BusinessException.unauthorized("Refresh token revoked or expired");
            return Long.valueOf(c.getSubject());
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw BusinessException.unauthorized("Invalid refresh token"); }
    }

    public void revoke(String token) {
        try { redis.delete("auth:refresh:" + parse(token).getId()); } catch (Exception ignored) { }
    }
}
