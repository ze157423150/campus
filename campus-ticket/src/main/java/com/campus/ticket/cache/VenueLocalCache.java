package com.campus.ticket.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class VenueLocalCache
{
    private final Cache<Long, String> cache;

    private long invalidationVersion = 0L;

    public VenueLocalCache(@Qualifier("venueDetailLocalCache") Cache<Long, String> cache)
    {
        this.cache = cache;
    }

    public String get(Long venueId)
    {
        return cache.getIfPresent(venueId);
    }

    public synchronized long currentVersion()
    {
        return invalidationVersion;
    }

    public synchronized void putIfUnchanged(Long venueId, String cacheJson, long expectedVersion)
    {
        if (invalidationVersion != expectedVersion)
        {
            return;
        }

        cache.put(venueId, cacheJson);
    }

    public synchronized void invalidate(Long venueId)
    {
        invalidationVersion++;
        cache.invalidate(venueId);
    }

    public CacheStats stats()
    {
        return cache.stats();
    }
}