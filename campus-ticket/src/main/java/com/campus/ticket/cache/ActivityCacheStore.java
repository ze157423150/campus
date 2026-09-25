package com.campus.ticket.cache;

import com.campus.ticket.constants.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityCacheStore {

    private final StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> WRITE_SCRIPT = loadScript("lua/write_activity_cache.lua");
    private static final DefaultRedisScript<Long> INVALIDATE_SCRIPT = loadScript("lua/invalidate_activity_cache.lua");


    private static DefaultRedisScript<Long> loadScript(String path)
    {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    public String getVersion(Long activityId){
        String versionKey = RedisConstants.ACTIVITY_CACHE_VERSION_KEY_PREFIX + activityId;
        String version = stringRedisTemplate.opsForValue().get(versionKey);
        return version == null ? "0":version;
    }

    public boolean writeIfVersionMatches(Long activityId, String expectedVersion, String cacheJson, Duration ttl){

        String versionKey = RedisConstants.ACTIVITY_CACHE_VERSION_KEY_PREFIX + activityId;
        String cacheKey = RedisConstants.ACTIVITY_DETAIL_KEY_PREFIX + activityId;
        Long result = stringRedisTemplate.execute(
                WRITE_SCRIPT,
                List.of(versionKey, cacheKey),
                expectedVersion,
                cacheJson,
                Long.toString(ttl.toMillis())
        );
        boolean written = Long.valueOf(1L).equals(result);
        if(!written){
            log.debug("放弃活动缓存写入，版本已变化，activityId={}", activityId);
        }
        return written;
    }
    public void invalidate(Long activityId)
    {
        String versionKey = RedisConstants.ACTIVITY_CACHE_VERSION_KEY_PREFIX + activityId;
        String cacheKey = RedisConstants.ACTIVITY_DETAIL_KEY_PREFIX + activityId;

        stringRedisTemplate.execute(
                INVALIDATE_SCRIPT,
                List.of(versionKey, cacheKey)
        );
    }
}
