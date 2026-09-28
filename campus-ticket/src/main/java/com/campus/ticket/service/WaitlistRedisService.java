package com.campus.ticket.service;

import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.dto.WaitlistHoldPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WaitlistRedisService
{
    private final StringRedisTemplate redis;

    private static final DefaultRedisScript<String> HOLD_SCRIPT = new DefaultRedisScript<>();

    static
    {
        HOLD_SCRIPT.setLocation(new ClassPathResource("lua/waitlist_hold.lua"));
        HOLD_SCRIPT.setResultType(String.class);
    }

    public void hold(Long taskId, Long quotaId, WaitlistHoldPayload payload)
    {
        if (!positive(taskId) || !positive(quotaId) || payload == null)
        {
            throw new IllegalArgumentException("名额接管任务参数不合法");
        }

        if (!positive(payload.activityId()) || !positive(payload.sourceUserId())
                || !positive(payload.registrationId()) || !positive(payload.epoch())
                || payload.sourceOrderId() == null || payload.sourceOrderId().isBlank())
        {
            throw new IllegalArgumentException("名额接管业务参数不合法");
        }

        Long activityId = payload.activityId();

        List<String> keys = List.of(
                RedisConstants.bookingInventoryKey(activityId),
                RedisConstants.bookingRequestsKey(activityId),
                RedisConstants.bookingPendingKey(activityId),
                RedisConstants.waitlistQuotaKey(activityId, quotaId)
        );

        String result = redis.execute(
                HOLD_SCRIPT,
                keys,
                taskId.toString(),
                quotaId.toString(),
                payload.sourceOrderId(),
                payload.sourceUserId().toString(),
                activityId.toString(),
                payload.epoch().toString(),
                payload.registrationId().toString()
        );

        if (!"APPLIED".equals(result) && !"ALREADY_APPLIED".equals(result))
        {
            throw new IllegalStateException("Redis名额接管未完成，taskId=" + taskId + "，result=" + result);
        }
    }

    private boolean positive(Long value)
    {
        return value != null && value > 0;
    }
}