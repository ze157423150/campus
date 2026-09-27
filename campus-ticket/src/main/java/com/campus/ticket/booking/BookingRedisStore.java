package com.campus.ticket.booking;

import com.campus.ticket.constants.RedisConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class BookingRedisStore
{
    private final StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> INITIALIZE_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<List> RESERVE_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<List> CLAIM_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> FAIL_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> CANCEL_SCRIPT = new DefaultRedisScript<>();

    static {
        INITIALIZE_SCRIPT.setLocation(new ClassPathResource("lua/booking_initialize.lua"));
        INITIALIZE_SCRIPT.setResultType(Long.class);

        RESERVE_SCRIPT.setLocation(new ClassPathResource("lua/booking_reserve.lua"));
        RESERVE_SCRIPT.setResultType(List.class);

        CLAIM_SCRIPT.setLocation(new ClassPathResource("lua/booking_claim.lua"));
        CLAIM_SCRIPT.setResultType(List.class);

        COMPLETE_SCRIPT.setLocation(new ClassPathResource("lua/booking_complete.lua"));
        COMPLETE_SCRIPT.setResultType(Long.class);

        FAIL_SCRIPT.setLocation(new ClassPathResource("lua/booking_fail.lua"));
        FAIL_SCRIPT.setResultType(Long.class);

        CANCEL_SCRIPT.setLocation(new ClassPathResource("lua/booking_cancel.lua"));
        CANCEL_SCRIPT.setResultType(Long.class);
    }

    public boolean initialize(
            Long activityId,
            long epoch,
            int remainingQuota,
            Instant registrationStart,
            Instant registrationEnd,
            Map<Long, String> registeredOwners
    )
    {
        if (activityId == null || activityId <= 0
                || epoch <= 0 || remainingQuota < 0
                || registrationStart == null || registrationEnd == null
                || !registrationStart.isBefore(registrationEnd)
                || registeredOwners == null) {
            throw new IllegalArgumentException("库存初始化参数不合法");
        }

        List<String> arguments = new ArrayList<>();

        arguments.add("ready");
        arguments.add("1");

        arguments.add("epoch");
        arguments.add(Long.toString(epoch));

        arguments.add("quota");
        arguments.add(Integer.toString(remainingQuota));

        arguments.add("start");
        arguments.add(Long.toString(registrationStart.toEpochMilli()));

        arguments.add("end");
        arguments.add(Long.toString(registrationEnd.toEpochMilli()));

        for (Map.Entry<Long, String> entry : registeredOwners.entrySet()) {
            if (entry.getKey() == null || entry.getKey() <= 0
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("已报名用户数据不合法");
            }

            arguments.add("u:" + entry.getKey());
            arguments.add(entry.getValue());
        }

        Long result = stringRedisTemplate.execute(
                INITIALIZE_SCRIPT,
                inventoryKeys(activityId),
                arguments.toArray()
        );

        if (Long.valueOf(1L).equals(result)) {
            return true;
        }

        if (Long.valueOf(0L).equals(result)) {
            return false;
        }

        throw new IllegalStateException("库存初始化失败，存在残留数据或返回结果异常");
    }

    public Long findEpoch(Long activityId)
    {
        String inventoryKey = RedisConstants.bookingInventoryKey(activityId);
        Object value = stringRedisTemplate.opsForHash().get(inventoryKey, "epoch");

        if (value == null)
        {
            return null;
        }

        long epoch = Long.parseLong(value.toString());

        if (epoch <= 0)
        {
            throw new IllegalStateException("Redis库存批次不合法");
        }

        return epoch;
    }


    public BookingReserveResult reserve(
            Long activityId,
            Long userId,
            String orderId,
            long epoch,
            Instant expiresAt
    )
    {
        if (activityId == null || activityId <= 0
                || userId == null || userId <= 0
                || orderId == null || orderId.isBlank()
                || epoch <= 0 || expiresAt == null) {
            throw new IllegalArgumentException("预占参数不合法");
        }

        List<?> result = stringRedisTemplate.execute(
                RESERVE_SCRIPT,
                inventoryKeys(activityId),
                Long.toString(epoch),
                userId.toString(),
                orderId,
                activityId.toString(),
                Long.toString(expiresAt.toEpochMilli())
        );

        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("Redis预占脚本没有返回结果");
        }

        String code = result.get(0).toString();
        String returnedOrderId = result.size() > 1 ? result.get(1).toString() : null;

        return new BookingReserveResult(code, returnedOrderId);
    }

    private List<String> inventoryKeys(Long activityId)
    {
        return List.of(
                RedisConstants.bookingInventoryKey(activityId),
                RedisConstants.bookingRequestsKey(activityId),
                RedisConstants.bookingPendingKey(activityId)
        );
    }

    public String findRequestJson(Long activityId, String orderId)
    {
        Object value = stringRedisTemplate.opsForHash().get(RedisConstants.bookingRequestsKey(activityId), orderId);
        return value == null ? null : value.toString();
    }
    public BookingDispatchMessage claimDueRequest(Long activityId)
    {
        return claimRequest(activityId, "");
    }

    public BookingDispatchMessage claimRequest(Long activityId, String orderId)
    {
        List<String> keys = List.of(
                RedisConstants.bookingPendingKey(activityId),
                RedisConstants.bookingRequestsKey(activityId)
        );

        String retryMillis = Long.toString(RedisConstants.BOOKING_DISPATCH_RETRY_INTERVAL.toMillis());
        List<?> result = stringRedisTemplate.execute(CLAIM_SCRIPT, keys, retryMillis, orderId);

        if (result == null)
        {
            throw new IllegalStateException("领取待投递申请没有返回结果");
        }

        if (result.isEmpty())
        {
            return null;
        }

        if (result.size() != 2)
        {
            throw new IllegalStateException("领取待投递申请返回格式异常");
        }

        return new BookingDispatchMessage(
                result.get(0).toString(),
                result.get(1).toString()
        );
    }

    public boolean markSucceeded(BookingMessage message, Long registrationId)
    {
        if (registrationId == null || registrationId <= 0)
        {
            throw new IllegalArgumentException("报名记录ID不合法");
        }

        List<String> keys = List.of(
                RedisConstants.bookingRequestsKey(message.activityId()),
                RedisConstants.bookingPendingKey(message.activityId())
        );

        Long result = stringRedisTemplate.execute(
                COMPLETE_SCRIPT,
                keys,
                message.orderId(),
                message.userId().toString(),
                message.activityId().toString(),
                message.epoch().toString(),
                registrationId.toString()
        );

        if (Long.valueOf(1L).equals(result))
        {
            return true;
        }

        if (Long.valueOf(2L).equals(result))
        {
            return false;
        }

        throw new IllegalStateException(
                "报名成功结果回写Redis失败，orderId=" + message.orderId()
        );
    }

    public void markFailed(BookingMessage message, String failureCode)
    {
        markFailed(message.activityId(), message.userId(), message.orderId(), message.epoch(), failureCode);
    }

    public void markFailed(Long activityId, Long userId, String orderId, Long epoch, String failureCode)
    {
        if (!"EXPIRED".equals(failureCode))
        {
            throw new IllegalArgumentException("当前只支持超时申请补偿");
        }

        Long result = stringRedisTemplate.execute(
                FAIL_SCRIPT,
                inventoryKeys(activityId),
                orderId,
                userId.toString(),
                activityId.toString(),
                epoch.toString(),
                failureCode
        );

        if (!Long.valueOf(1L).equals(result))
        {
            throw new IllegalStateException(
                    "报名失败补偿未完成，orderId=" + orderId
            );
        }
    }
    public void markCancelled(
            Long activityId,
            Long userId,
            String orderId,
            Long epoch,
            Long registrationId)
    {
        if (registrationId == null || registrationId <= 0)
        {
            throw new IllegalArgumentException("报名记录ID不合法");
        }

        Long result = stringRedisTemplate.execute(
                CANCEL_SCRIPT,
                inventoryKeys(activityId),
                orderId,
                userId.toString(),
                activityId.toString(),
                epoch.toString(),
                registrationId.toString()
        );

        if (!Long.valueOf(1L).equals(result))
        {
            throw new IllegalStateException(
                    "取消结果同步Redis未完成，orderId=" + orderId
            );
        }
    }
}
