package com.campus.ticket.service;

import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.constants.WaitlistStatus;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.entity.Activity;
import com.campus.ticket.entity.ActivityWaitlist;
import com.campus.ticket.entity.Registration;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.ActivityMapper;
import com.campus.ticket.mapper.ActivityWaitlistMapper;
import com.campus.ticket.mapper.RegistrationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.campus.ticket.dto.WaitlistQueryResponse;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WaitlistService
{
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ActivityMapper activityMapper;
    private final ActivityWaitlistMapper waitlistMapper;
    private final RegistrationMapper registrationMapper;
    private final StringRedisTemplate redis;
    private final com.campus.ticket.mapper.WaitlistWorkflowMapper workflowMapper;

    @Transactional
    public ActivityWaitlist join(Long activityId)
    {
        Long userId = UserHolder.getUserId();

        validateActivityId(activityId);

        Activity activity = activityMapper.findByIdForUpdate(activityId);

        if (activity == null)
        {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "活动不存在");
        }

        ActivityWaitlist existing = waitlistMapper.findActive(activityId, userId);

        if (existing != null)
        {
            return existing;
        }

        validateActivity(activity);

        Registration registration = registrationMapper.findByUserAndActivityForUpdate(userId, activityId);

        if (registration != null && !"CANCELLED".equals(registration.getStatus()))
        {
            throw conflict("ALREADY_REGISTERED", "已有有效报名，不能加入候补");
        }

        validateRedisInventory(activityId, userId);

        ActivityWaitlist waitlist = new ActivityWaitlist();
        waitlist.setActivityId(activityId);
        waitlist.setUserId(userId);
        waitlist.setStatus(WaitlistStatus.WAITING);

        int inserted = waitlistMapper.insert(waitlist);

        if (inserted != 1 || waitlist.getId() == null)
        {
            throw new IllegalStateException("创建候补记录失败");
        }

        return waitlistMapper.findByIdAndUserId(waitlist.getId(), userId);
    }

    private void validateActivity(Activity activity)
    {
        if (!"PUBLISHED".equals(activity.getStatus()))
        {
            throw conflict("ACTIVITY_NOT_AVAILABLE", "活动当前不允许加入候补");
        }

        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);

        if (now.isBefore(activity.getRegistrationStartTime()))
        {
            throw conflict("REGISTRATION_NOT_STARTED", "报名尚未开始");
        }

        if (!now.isBefore(activity.getRegistrationEndTime()) || !now.isBefore(activity.getStartTime()))
        {
            throw conflict("WAITLIST_CLOSED", "该活动已停止接受候补");
        }
    }

    private void validateRedisInventory(Long activityId, Long userId)
    {
        String key = RedisConstants.bookingInventoryKey(activityId);
        List<Object> fields = List.of("ready", "epoch", "quota", "u:" + userId);
        List<Object> values;

        try
        {
            values = redis.opsForHash().multiGet(key, fields);
        }
        catch (DataAccessException e)
        {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "WAITLIST_UNAVAILABLE", "暂时无法校验报名库存，请稍后重试", e);
        }

        if (values == null || values.size() != 4 || !"1".equals(asString(values.get(0))))
        {
            throw unavailable("活动报名库存尚未准备好");
        }

        long epoch = parseNumber(values.get(1));
        long quota = parseNumber(values.get(2));

        if (epoch <= 0 || quota < 0)
        {
            throw unavailable("活动报名库存数据异常");
        }

        if (values.get(3) != null)
        {
            throw conflict("BOOKING_ALREADY_EXISTS", "已有报名占用或申请正在处理，请先查询报名结果");
        }

        if (quota > 0)
        {
            throw conflict("QUOTA_AVAILABLE", "活动仍有名额，请直接报名");
        }
    }

    private String asString(Object value)
    {
        return value == null ? null : value.toString();
    }

    private long parseNumber(Object value)
    {
        if (value == null)
        {
            throw unavailable("活动报名库存数据不完整");
        }

        try
        {
            return Long.parseLong(value.toString());
        }
        catch (NumberFormatException e)
        {
            throw unavailable("活动报名库存数据格式异常");
        }
    }

    private BusinessException conflict(String code, String message)
    {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    private BusinessException unavailable(String message)
    {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "WAITLIST_UNAVAILABLE", message);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public WaitlistQueryResponse findCurrent(Long activityId)
    {
        Long userId = UserHolder.getUserId();
        validateActivityId(activityId);

        ActivityWaitlist waitlist = waitlistMapper.findActive(activityId, userId);

        if (waitlist == null)
        {
            throw new BusinessException(HttpStatus.NOT_FOUND, "WAITLIST_NOT_FOUND", "没有该活动的有效候补记录");
        }

        Long waitingAhead = null;

        if (WaitlistStatus.WAITING.equals(waitlist.getStatus()))
        {
            waitingAhead = waitlistMapper.countWaitingAhead(activityId, waitlist.getId());
        }

        var offer = WaitlistStatus.OFFERED.equals(waitlist.getStatus()) ? workflowMapper.byWaitlist(waitlist.getId()) : null;
        return new WaitlistQueryResponse(waitlist.getId(), activityId, waitlist.getStatus(), waitingAhead, waitlist.getCreateTime(),
                offer == null ? null : offer.getId(), offer == null ? null : offer.getStatus(),
                offer != null && "OFFERED".equals(offer.getStatus()) ? offer.getConfirmDeadline() : null);
    }

    @Transactional
    public void cancelWaiting(Long activityId, Long waitlistId)
    {
        Long userId = UserHolder.getUserId();
        validateActivityId(activityId);

        if (waitlistId == null || waitlistId <= 0)
        {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "候补记录ID必须为正数");
        }

        Activity activity = activityMapper.findByIdForUpdate(activityId);

        if (activity == null)
        {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "活动不存在");
        }

        ActivityWaitlist waitlist = waitlistMapper.findByIdAndUserId(waitlistId, userId);

        if (waitlist == null || !activityId.equals(waitlist.getActivityId()))
        {
            throw new BusinessException(HttpStatus.NOT_FOUND, "WAITLIST_NOT_FOUND", "候补记录不存在");
        }

        if (WaitlistStatus.CANCELLED.equals(waitlist.getStatus()))
        {
            return;
        }

        if (WaitlistStatus.OFFERED.equals(waitlist.getStatus()))
        {
            throw conflict("WAITLIST_ALREADY_OFFERED", "已获得候补邀请，请通过放弃邀请流程处理");
        }

        if (!WaitlistStatus.WAITING.equals(waitlist.getStatus()))
        {
            throw conflict("WAITLIST_STATE_CONFLICT", "该候补记录已结束，不能退出排队");
        }

        int updated = waitlistMapper.cancelWaiting(waitlistId, activityId, userId);

        if (updated != 1)
        {
            throw new IllegalStateException("退出候补失败，记录状态不一致");
        }
    }

    private void validateActivityId(Long activityId)
    {
        if (activityId == null || activityId <= 0)
        {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "活动ID必须为正数");
        }
    }

}
