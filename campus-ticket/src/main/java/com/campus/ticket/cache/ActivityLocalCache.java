package com.campus.ticket.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ActivityLocalCache
{
    private final Cache<Long, String> cache;

    private long invalidationVersion = 0L;

    public ActivityLocalCache(@Qualifier("activityDetailLocalCache") Cache<Long, String> cache)
    {
        this.cache = cache;
    }

    public String get(Long activityId)
    {
        return cache.getIfPresent(activityId);
    }

    public synchronized long currentVersion()
    {
        return invalidationVersion;
    }

    public synchronized void putIfUnchanged(Long activityId, String cacheJson, long expectedVersion)
    {
        if (invalidationVersion != expectedVersion)
        {
            return;
        }

        cache.put(activityId, cacheJson);
    }

    public synchronized void invalidate(Long activityId)
    {
        invalidationVersion++;
        cache.invalidate(activityId);
    }

    public CacheStats stats()
    {
        return cache.stats();
    }
}