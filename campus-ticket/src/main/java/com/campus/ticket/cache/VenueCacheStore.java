package com.campus.ticket.cache;

import com.campus.ticket.constants.RedisConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class VenueCacheStore
{
    private final StringRedisTemplate redis;
    private final VenueLocalCache localCache;
    private static final DefaultRedisScript<Long> WRITE_SCRIPT = loadScript("lua/write_venue_cache.lua");
    private static final DefaultRedisScript<Long> INVALIDATE_SCRIPT = loadScript("lua/invalidate_venue_cache.lua");

    private static DefaultRedisScript<Long> loadScript(String path)
    {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    public String getVersion(Long id)
    {
        String value = redis.opsForValue().get(RedisConstants.VENUE_CACHE_VERSION_KEY_PREFIX + id);
        return value == null ? "0" : value;
    }

    public boolean writeIfVersionMatches(Long id, String expectedVersion, String json, Duration ttl)
    {
        Long result = redis.execute(WRITE_SCRIPT, List.of(RedisConstants.VENUE_CACHE_VERSION_KEY_PREFIX + id, RedisConstants.VENUE_DETAIL_KEY_PREFIX + id), expectedVersion, json, Long.toString(ttl.toMillis()));
        boolean written = Long.valueOf(1).equals(result);
        if (written) localCache.invalidate(id);
        return written;
    }

    public void invalidate(Long id)
    {
        localCache.invalidate(id);
        try
        {
            Long result = redis.execute(INVALIDATE_SCRIPT, List.of(RedisConstants.VENUE_CACHE_VERSION_KEY_PREFIX + id, RedisConstants.VENUE_DETAIL_KEY_PREFIX + id));
            if (!Long.valueOf(1).equals(result)) throw new IllegalStateException("场馆缓存失效脚本未确认成功");
        }
        finally
        {
            localCache.invalidate(id);
        }
    }
}
