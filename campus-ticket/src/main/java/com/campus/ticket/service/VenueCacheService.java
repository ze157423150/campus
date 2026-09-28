package com.campus.ticket.service;

import com.campus.ticket.cache.VenueCacheStore;
import com.campus.ticket.cache.VenueLocalCache;
import com.campus.ticket.cache.VenueRefreshDispatcher;
import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.dto.VenueCacheData;
import com.campus.ticket.entity.Venue;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.VenueMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class VenueCacheService
{
    private final VenueMapper venueMapper;
    private final StringRedisTemplate redis;
    private final RedissonClient redissonClient;
    private final JsonMapper jsonMapper;
    private final VenueLocalCache localCache;
    private final VenueCacheStore cacheStore;
    private final VenueRefreshDispatcher refreshDispatcher;

    public Venue findById(Long id)
    {
        String json = localCache.get(id);
        if (json != null) return readCached(id, json);
        json = readRedisAndFillLocal(id);
        if (json != null) return readCached(id, json);

        RLock lock = redissonClient.getLock(RedisConstants.VENUE_REBUILD_LOCK_PREFIX + id);
        boolean acquired = false;
        try
        {
            acquired = lock.tryLock(RedisConstants.VENUE_REBUILD_LOCK_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            // 即使没有拿到锁，也最后检查一次其他请求是否已完成重建。
            json = readRedisAndFillLocal(id);
            if (json != null) return readCached(id, json);
            if (!acquired) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "VENUE_CACHE_BUSY", "场馆查询繁忙，请稍后重试");

            String version = cacheStore.getVersion(id);
            Venue venue = venueMapper.findOpenById(id);
            writeCache(id, venue, version);
            return venue;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "REQUEST_INTERRUPTED", "请求等待被中断，请重试", e);
        }
        finally
        {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private String readRedisAndFillLocal(Long id)
    {
        long expectedVersion = localCache.currentVersion();
        String json = redis.opsForValue().get(RedisConstants.VENUE_DETAIL_KEY_PREFIX + id);
        if (json != null) localCache.putIfUnchanged(id, json, expectedVersion);
        return json;
    }

    private Venue readCached(Long id, String json)
    {
        if (RedisConstants.CACHE_NULL_VALUE.equals(json)) return null;
        VenueCacheData data = jsonMapper.readValue(json, VenueCacheData.class);
        if (!Instant.now().isBefore(data.expireAt()))
        {
            refreshDispatcher.submit(id, () -> refresh(id));
        }
        return data.data();
    }

    private void writeCache(Long id, Venue venue, String version)
    {
        if (venue == null)
        {
            cacheStore.writeIfVersionMatches(id, version, RedisConstants.CACHE_NULL_VALUE, RedisConstants.VENUE_NULL_TTL);
            return;
        }
        long jitter = ThreadLocalRandom.current().nextLong(RedisConstants.VENUE_DETAIL_TTL_JITTER.toSeconds() + 1);
        Duration logicalTtl = RedisConstants.VENUE_DETAIL_TTL.plusSeconds(jitter);
        String json = jsonMapper.writeValueAsString(new VenueCacheData(venue, Instant.now().plus(logicalTtl)));
        cacheStore.writeIfVersionMatches(id, version, json, RedisConstants.VENUE_DETAIL_PHYSICAL_TTL);
    }

    private void refresh(Long id)
    {
        RLock lock = redissonClient.getLock(RedisConstants.VENUE_REBUILD_LOCK_PREFIX + id);
        boolean acquired = false;
        try
        {
            acquired = lock.tryLock();
            if (!acquired) return;
            // 后台检查必须读 L2，不能再从本地旧数据开始判断。
            String json = redis.opsForValue().get(RedisConstants.VENUE_DETAIL_KEY_PREFIX + id);
            if (json == null || RedisConstants.CACHE_NULL_VALUE.equals(json)) return;
            VenueCacheData data = jsonMapper.readValue(json, VenueCacheData.class);
            if (Instant.now().isBefore(data.expireAt())) return;
            String version = cacheStore.getVersion(id);
            writeCache(id, venueMapper.findOpenById(id), version);
        }
        finally
        {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
