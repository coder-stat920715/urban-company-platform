package com.urbancompany.platform.location;

import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Redis GEOADD / GEOSEARCH index of online partners + a heartbeat key (TTL) to evict stale positions. */
@Component
@RequiredArgsConstructor
public class PartnerGeoIndex {
    public static final String KEY = "geo:partners:online";
    public static final Duration HEARTBEAT_TTL = Duration.ofSeconds(30);
    private final StringRedisTemplate redis;

    public record Nearby(Long partnerId, double distanceKm) {}

    public void update(Long partnerId, double lat, double lng) {
        redis.opsForGeo().add(KEY, new Point(lng, lat), String.valueOf(partnerId));
        redis.opsForValue().set("partner:hb:" + partnerId, lat + "," + lng, HEARTBEAT_TTL);
    }

    public void remove(Long partnerId) {
        redis.opsForGeo().remove(KEY, String.valueOf(partnerId));
        redis.delete("partner:hb:" + partnerId);
    }

    public boolean isAlive(Long partnerId) { return Boolean.TRUE.equals(redis.hasKey("partner:hb:" + partnerId)); }

    public List<Nearby> search(double lat, double lng, double radiusKm) {
        GeoResults<RedisGeoCommands.GeoLocation<String>> res = redis.opsForGeo().search(KEY,
                GeoReference.fromCoordinate(lng, lat), new Distance(radiusKm, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending());
        List<Nearby> out = new ArrayList<>();
        if (res == null) return out;
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> r : res) {
            Long id = Long.valueOf(r.getContent().getName());
            if (isAlive(id)) out.add(new Nearby(id, r.getDistance().getValue()));
        }
        return out;
    }
}
