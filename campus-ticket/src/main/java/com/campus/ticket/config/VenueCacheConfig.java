package com.campus.ticket.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class VenueCacheConfig
{
    @Bean("venueDetailLocalCache")
    public Cache<Long, String> venueDetailLocalCache(@Value("${campus.cache.venue-local.maximum-size:500}") long maximumSize, @Value("${campus.cache.venue-local.ttl-seconds:2}") long ttlSeconds)
    {
        if (maximumSize <= 0 || ttlSeconds <= 0) throw new IllegalArgumentException("场馆本地缓存容量和有效期必须大于零");
        return Caffeine.newBuilder().maximumSize(maximumSize).expireAfterWrite(Duration.ofSeconds(ttlSeconds)).recordStats().build();
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor venueCacheExecutor()
    {
        AtomicInteger threadNumber = new AtomicInteger();
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(100), task -> {
            return new Thread(task, "venue-cache-refresh-" + threadNumber.incrementAndGet());
        }, new ThreadPoolExecutor.AbortPolicy());
    }
}
