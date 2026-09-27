package com.campus.ticket.booking;

import com.campus.ticket.context.UserHolder;
import com.campus.ticket.entity.Activity;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.ActivityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BookingInventoryService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final ActivityMapper activityMapper;
    private final BookingMapper bookingMapper;
    private final BookingRedisStore bookingRedisStore;

    @Transactional
    public void initialize(Long activityId){
        UserHolder.requireAdmin();

        if(activityId == null || activityId <=0){
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "活动ID必须为正数"
            );
        }
        Activity activity = activityMapper.findByIdForUpdate(activityId);
        if(activity == null){
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "ACTIVITY_NOT_FOUND",
                    "活动不存在"
            );
        }

        if (!"PUBLISHED".equals(activity.getStatus()))
        {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "ACTIVITY_NOT_PUBLISHED",
                    "只有已发布的活动可以初始化报名库存"
            );
        }

        Instant registrationStart = activity.getRegistrationStartTime().atZone(BUSINESS_ZONE).toInstant();
        Instant registrationEnd = activity.getRegistrationEndTime().atZone(BUSINESS_ZONE).toInstant();

        if (!registrationStart.isBefore(registrationEnd)
                || !Instant.now().isBefore(registrationEnd))
        {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_REGISTRATION_TIME",
                    "报名时间不合法或报名已经结束"
            );
        }

        List<Map<String, Object>> rows = bookingMapper.owners(activityId);
        Map<Long, String> registeredOwners = new HashMap<>();

        for (Map<String, Object> row : rows)
        {
            Long userId = ((Number) row.get("userId")).longValue();
            String owner = row.get("owner").toString();

            registeredOwners.put(userId, owner);
        }
        Integer remainingQuota = activity.getRemainingQuota();
        Integer totalQuota = activity.getTotalQuota();

        if (remainingQuota == null || totalQuota == null
                || remainingQuota < 0 || remainingQuota > totalQuota
                || totalQuota - remainingQuota != registeredOwners.size())
        {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVENTORY_DATA_INCONSISTENT",
                    "活动剩余名额与有效报名人数不一致，请先检查数据库"
            );
        }
        boolean initialized = bookingRedisStore.initialize(
                activityId,
                1L,
                remainingQuota,
                registrationStart,
                registrationEnd,
                registeredOwners
        );

        if (!initialized)
        {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "BOOKING_INVENTORY_EXISTS",
                    "活动库存已经初始化，请勿重复初始化"
            );
        }
    }
}
