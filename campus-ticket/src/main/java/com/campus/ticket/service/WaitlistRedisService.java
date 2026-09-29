package com.campus.ticket.service;

import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.dto.WaitlistHoldPayload;
import com.campus.ticket.dto.WaitlistReturnPayload;
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
    private final tools.jackson.databind.json.JsonMapper jsonMapper;
    private static final DefaultRedisScript<String> TRANSITION_SCRIPT = new DefaultRedisScript<>();

    private static final DefaultRedisScript<String> HOLD_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<String> RETURN_SCRIPT = new DefaultRedisScript<>();

    static
    {
        TRANSITION_SCRIPT.setLocation(new ClassPathResource("lua/waitlist_transition.lua"));
        TRANSITION_SCRIPT.setResultType(String.class);
        HOLD_SCRIPT.setLocation(new ClassPathResource("lua/waitlist_hold.lua"));
        HOLD_SCRIPT.setResultType(String.class);

        RETURN_SCRIPT.setLocation(new ClassPathResource("lua/waitlist_return.lua"));
        RETURN_SCRIPT.setResultType(String.class);
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

    public String transition(Long taskId, Long quotaId, Long version, String operation, com.campus.ticket.dto.WaitlistTransitionPayload payload)
    {
        if (!positive(taskId) || !positive(quotaId) || !positive(version) || payload == null
                || !positive(payload.activityId()) || !positive(payload.epoch()) || !positive(payload.offerId()) || !positive(payload.userId()))
        {
            throw new IllegalArgumentException("候补流转参数不合法");
        }
        java.util.Map<String, String> args = new java.util.HashMap<>();
        args.put("activityId", payload.activityId().toString());
        args.put("epoch", payload.epoch().toString());
        args.put("offerId", payload.offerId().toString());
        args.put("userId", payload.userId().toString());
        if (payload.orderId() != null) args.put("orderId", payload.orderId());
        if (payload.registrationId() != null) args.put("registrationId", payload.registrationId().toString());
        String result = redis.execute(TRANSITION_SCRIPT, List.of(RedisConstants.bookingInventoryKey(payload.activityId()), RedisConstants.bookingRequestsKey(payload.activityId()), RedisConstants.waitlistQuotaKey(payload.activityId(), quotaId)), taskId.toString(), quotaId.toString(), Long.toString(version - 1), version.toString(), operation, jsonMapper.writeValueAsString(args));
        if (!"APPLIED".equals(result) && !("OFFER".equals(operation) && "OCCUPIED".equals(result)))
        {
            throw new IllegalStateException("候补Redis流转未完成，taskId=" + taskId + ", result=" + result);
        }
        return result;
    }

    public void returnQuota(Long taskId, Long quotaId, Long quotaVersion, WaitlistReturnPayload payload)
    {
        if (!positive(taskId) || !positive(quotaId) || !positive(quotaVersion) || payload == null)
        {
            throw new IllegalArgumentException("名额归还任务参数不合法");
        }

        if (!positive(payload.activityId()) || !positive(payload.epoch())
                || payload.sourceOrderId() == null || payload.sourceOrderId().isBlank())
        {
            throw new IllegalArgumentException("名额归还业务参数不合法");
        }

        long expectedVersion = quotaVersion - 1;

        List<String> keys = List.of(
                RedisConstants.bookingInventoryKey(payload.activityId()),
                RedisConstants.waitlistQuotaKey(payload.activityId(), quotaId)
        );

        String result = redis.execute(
                RETURN_SCRIPT,
                keys,
                taskId.toString(),
                quotaId.toString(),
                payload.activityId().toString(),
                payload.sourceOrderId(),
                payload.epoch().toString(),
                Long.toString(expectedVersion),
                quotaVersion.toString()
        );

        if (!"APPLIED".equals(result) && !"ALREADY_APPLIED".equals(result))
        {
            throw new IllegalStateException("Redis名额归还未完成，taskId=" + taskId + "，result=" + result);
        }
    }
}
