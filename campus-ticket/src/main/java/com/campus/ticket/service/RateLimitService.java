package com.campus.ticket.service;

import com.campus.ticket.dto.RateLimitResult;
import com.campus.ticket.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<List> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<List> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();

    static
    {
        SLIDING_WINDOW_SCRIPT.setLocation(new ClassPathResource("lua/rate_limit_sliding_window.lua"));
        SLIDING_WINDOW_SCRIPT.setResultType(List.class);

        TOKEN_BUCKET_SCRIPT.setLocation(new ClassPathResource("lua/rate_limit_token_bucket.lua"));
        TOKEN_BUCKET_SCRIPT.setResultType(List.class);
    }

    public RateLimitResult tryAcquireSlidingWindow(String key, Duration window, int maxRequests)
    {
        if (key == null || key.isBlank())
        {
            throw new IllegalArgumentException("限流 key 不能为空");
        }

        if (window == null || window.isNegative() || window.isZero())
        {
            throw new IllegalArgumentException("限流窗口必须大于零");
        }

        long windowMillis = window.toMillis();

        if (windowMillis <= 0 || maxRequests <= 0)
        {
            throw new IllegalArgumentException("窗口至少为1毫秒，允许次数必须大于零");
        }

        String requestId = UUID.randomUUID().toString();
        List<?> result;

        try {
            result = stringRedisTemplate.execute(
                    SLIDING_WINDOW_SCRIPT,
                    List.of(key),
                    Long.toString(windowMillis),
                    Integer.toString(maxRequests),
                    requestId
            );
        } catch (DataAccessException e) {
            log.error("Redis限流检查失败，key={}", key, e);

            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RATE_LIMIT_UNAVAILABLE",
                    "访问控制服务暂不可用，请稍后重试",
                    e
            );
        }
        return parseResult(result);

    }

    public RateLimitResult tryAcquireTokenBucket(String key, int capacity, int refillPerSecond)
    {
        if (key == null || key.isBlank())
        {
            throw new IllegalArgumentException("限流 key 不能为空");
        }

        if (capacity <= 0 || refillPerSecond <= 0)
        {
            throw new IllegalArgumentException("桶容量和每秒补充数量必须大于零");
        }

        List<?> result;

        try
        {
            result = stringRedisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(key),
                    Integer.toString(capacity),
                    Integer.toString(refillPerSecond)
            );
        }
        catch (DataAccessException e)
        {
            log.error("Redis令牌桶限流检查失败，key={}", key, e);

            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RATE_LIMIT_UNAVAILABLE",
                    "访问控制服务暂不可用，请稍后重试",
                    e
            );
        }

        return parseResult(result);
    }

    private RateLimitResult parseResult(List<?> result)
    {
        if (result == null || result.size() != 3)
        {
            throw new IllegalStateException("限流脚本返回格式异常");
        }

        long allowed = Long.parseLong(result.get(0).toString());
        long remaining = Long.parseLong(result.get(1).toString());
        long retryAfterMillis = Long.parseLong(result.get(2).toString());

        if ((allowed != 0 && allowed != 1) || remaining < 0 || retryAfterMillis < 0)
        {
            throw new IllegalStateException("限流脚本返回值异常");
        }

        return new RateLimitResult(allowed == 1, remaining, retryAfterMillis);
    }
}
