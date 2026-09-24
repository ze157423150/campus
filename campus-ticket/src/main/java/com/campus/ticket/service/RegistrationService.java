package com.campus.ticket.service;

import com.campus.ticket.dto.RegistrationDetail;
import com.campus.ticket.entity.Activity;
import com.campus.ticket.entity.Registration;
import com.campus.ticket.event.ActivityChangedEvent;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.ActivityMapper;
import com.campus.ticket.mapper.RegistrationMapper;
import com.campus.ticket.mapper.UserMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RegistrationService {

    private final UserMapper userMapper;
    private final ActivityMapper activityMapper;
    private final RegistrationMapper registrationMapper;
    private final ApplicationEventPublisher eventPublisher;

    public RegistrationService(UserMapper userMapper, ActivityMapper activityMapper, RegistrationMapper registrationMapper, ApplicationEventPublisher eventPublisher) {
        this.userMapper = userMapper;
        this.activityMapper = activityMapper;
        this.registrationMapper = registrationMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Long register(Long userId,Long activityId) {
        if (userId == null || userId <= 0 || activityId == null || activityId <= 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "用户ID和活动ID必须为正数"
            );
        }
        if(userMapper.countById(userId) == 0){
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "USER_NOT_FOUND",
                    "用户不存在"
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

        if (!"PUBLISHED".equals(activity.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "ACTIVITY_NOT_PUBLISHED",
                    "活动未发布或已取消，不能报名"
            );
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getRegistrationStartTime())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "REGISTRATION_NOT_STARTED",
                    "报名尚未开始"
            );
        }

        if (!now.isBefore(activity.getRegistrationEndTime())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "REGISTRATION_CLOSED",
                    "报名已结束"
            );
        }
        // 已持有活动行锁，再读取报名的最新状态
        Registration existing = registrationMapper.findByUserAndActivityForUpdate(userId, activityId);

        if (existing != null) {
            if ("REGISTERED".equals(existing.getStatus())) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "DUPLICATE_REGISTRATION",
                        "请勿重复报名"
                );
            }

            if (!"CANCELLED".equals(existing.getStatus())) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "INVALID_REGISTRATION_STATUS",
                        "报名状态异常，无法报名"
                );
            }
        }

// 首次报名和恢复报名，都必须重新获取名额
        int affectedRows = activityMapper.deductQuota(activityId);
        if (affectedRows != 1) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "QUOTA_EXHAUSTED",
                    "活动名额已满"
            );
        }

// 已有取消记录：恢复原记录
        if (existing != null) {
            int changedRows = registrationMapper.reactivate(existing.getId());

            if (changedRows != 1) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "REGISTRATION_STATE_CONFLICT",
                        "报名状态已变化，请重新查询"
                );
            }
            eventPublisher.publishEvent(new ActivityChangedEvent(activityId));
            return existing.getId();
        }

// 没有记录：首次报名
        Registration registration = new Registration();
        registration.setUserId(userId);
        registration.setActivityId(activityId);

        try {
            registrationMapper.insert(registration);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "DUPLICATE_REGISTRATION",
                    "请勿重复报名",
                    e
            );
        }
        eventPublisher.publishEvent(new ActivityChangedEvent(activityId));
        return registration.getId();
    }

    public List<RegistrationDetail> findByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "用户ID必须为正数"
            );
        }

        if (userMapper.countById(userId) == 0) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"
            );
        }

        return registrationMapper.findByUserId(userId);
    }

    @Transactional
    public void cancel(Long userId,Long registrationId){
        if(userId == null ||userId<=0||registrationId==null|| registrationId <= 0){
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "用户ID和报名ID必须为正数"
            );
        }
        Registration registration = registrationMapper.findByIdAndUserId(registrationId, userId);
        if(registration == null){
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "REGISTRATION_NOT_FOUND",
                    "报名记录不存在或不属于当前用户"
            );
        }

        Activity activity = activityMapper.findByIdForUpdate(registration.getActivityId());
        if(activity == null){
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "ACTIVITY_NOT_FOUND",
                    "活动不存在"
            );
        }

        Registration latest = registrationMapper.findByUserAndActivityForUpdate(userId, activity.getId());
        if (latest == null || !registrationId.equals(latest.getId())) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "REGISTRATION_NOT_FOUND",
                    "报名记录不存在"
            );
        }
        if ("CANCELLED".equals(latest.getStatus())) {
            return;
        }

        if (!"REGISTERED".equals(latest.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_REGISTRATION_STATUS",
                    "报名状态异常，无法取消"
            );
        }
        if(!LocalDateTime.now().isBefore(activity.getStartTime())){
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "CANCELLATION_CLOSED",
                    "活动已开始，不能取消报名"
            );
        }
        // 1. 将报名状态从 REGISTERED 改为 CANCELLED
        int changedRows = registrationMapper.cancel(registrationId, userId);

        if (changedRows == 0) {
            return;
        }

        int restoredRows = activityMapper.restoreQuota(registration.getActivityId());

        if (restoredRows != 1) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "QUOTA_RESTORE_FAILED",
                    "名额归还失败，请联系管理员检查"
            );
        }
        eventPublisher.publishEvent(new ActivityChangedEvent(registration.getActivityId()));
    }
}
