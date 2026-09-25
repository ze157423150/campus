package com.campus.ticket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class CacheExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor activityCacheExecutor() {
        AtomicInteger threadNumber = new AtomicInteger();

        return new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(100),
                task -> new Thread(
                        task,
                        "activity-cache-refresh-"
                                + threadNumber.incrementAndGet()
                ),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}