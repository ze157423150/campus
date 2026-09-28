package com.campus.ticket.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LocalCacheConfig
{
    @Bean("activityDetailLocalCache")
    public Cache<Long, String> activityDetailLocalCache(@Value("${campus.cache.activity-local.maximum-size:1000}") long maximumSize, @Value("${campus.cache.activity-local.ttl-seconds:2}") long ttlSeconds)
    {
        if (maximumSize <= 0 || ttlSeconds <= 0)
        {
            throw new IllegalArgumentException("本地缓存容量和有效期必须大于零");
        }

        return Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .recordStats()
                .build();
    }
}