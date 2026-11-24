package com.urbancompany.platform.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    /** Redisson is used for distributed locks (slot reservation, job acceptance, dispatch). Caching/Geo use Spring Data Redis. */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redisson(@Value("${spring.data.redis.host}") String host, @Value("${spring.data.redis.port}") int port) {
        Config c = new Config();
        c.useSingleServer().setAddress("redis://" + host + ":" + port).setConnectionMinimumIdleSize(2).setConnectionPoolSize(16);
        return Redisson.create(c);
    }
}
