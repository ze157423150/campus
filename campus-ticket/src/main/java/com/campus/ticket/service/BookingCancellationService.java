package com.campus.ticket.service;

import com.campus.ticket.booking.BookingMapper;
import com.campus.ticket.booking.BookingOrder;
import com.campus.ticket.booking.BookingOrderStatus;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.entity.Activity;
import com.campus.ticket.entity.Registration;
import com.campus.ticket.event.ActivityChangedEvent;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.ActivityMapper;
import com.campus.ticket.mapper.RegistrationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

@RequiredArgsConstructor
@Service

public class BookingCancellationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ActivityMapper activityMapper;
    private final RegistrationMapper registrationMapper;
    private final BookingMapper bookingMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final com.campus.ticket.mapper.WaitlistQuotaMapper waitlistQuotaMapper;
    private final WaitlistRedisTaskService waitlistTasks;

    @Transactional
    public BookingOrder cancelInDatabase(Long activityId,String orderId){
        Long userId = UserHolder.getUserId();
        if(activityId == null || activityId<=0||orderId==null||orderId.isBlank()||orderId.length()>36){
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "活动ID或订单编号不合法"
            );
        }

        Activity activity = activityMapper.findByIdForUpdate(activityId);
        if(activity == null){
            throw  new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "BOOKING_ORDER_NOT_FOUND",
                    "报名订单不存在"
            );
        }
        BookingOrder order = bookingMapper.lock(orderId);
        if (order == null || !userId.equals(order.getUserId()) || !activityId.equals(order.getActivityId()))
        {
            throw  new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "BOOKING_ORDER_NOT_FOUND",
                    "报名订单不存在"
            );
        }

        if (BookingOrderStatus.CANCELLED.equals(order.getStatus()))
        {
            return order;
        }

        if (!BookingOrderStatus.SUCCEEDED.equals(order.getStatus()))
        {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "ORDER_NOT_CANCELLABLE",
                    "当前只支持取消报名成功的订单"
            );
        }

        if (!"PUBLISHED".equals(activity.getStatus()))
        {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "ACTIVITY_STATE_CONFLICT",
                    "活动状态已变化，请重新查询"
            );
        }

        if (!LocalDateTime.now(BUSINESS_ZONE).isBefore(activity.getStartTime()))
        {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "CANCELLATION_CLOSED",
                    "活动已开始，不能取消报名"
            );
        }
        Registration registration = registrationMapper.findByUserAndActivityForUpdate(userId, activityId);
        if (registration == null
                || !registration.getId().equals(order.getRegistrationId())
                || !"REGISTERED".equals(registration.getStatus()))
        {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "REGISTRATION_STATE_CONFLICT",
                    "订单与报名记录状态不一致"
            );
        }

        //取消报名
        int cancel = registrationMapper.cancel(registration.getId(), userId);

        if(cancel!=1){
            throw new IllegalStateException("取消报名记录失败");
        }
        // 名额先由候补流程保留；分配失败且无人候补时才回补公开库存。
        //将已报名记录修改为
        int updatedOrders = bookingMapper.cancelSucceeded(orderId);

        if (updatedOrders != 1)
        {
            throw new IllegalStateException("更新订单取消状态失败");
        }

        bookingMapper.log(
                orderId,
                "CANCELLED",
                "用户取消报名，名额交由候补流程处理"
        );
        eventPublisher.publishEvent(new ActivityChangedEvent(activityId));
        order.setStatus(BookingOrderStatus.CANCELLED);
        order.setRedisDirty(true);
        com.campus.ticket.entity.WaitlistQuota quota = new com.campus.ticket.entity.WaitlistQuota();
        quota.setActivityId(activityId);
        quota.setSourceOrderId(orderId);
        quota.setStatus("HELD");
        quota.setVersion(0L);
        if (waitlistQuotaMapper.insert(quota) != 1) throw new IllegalStateException("创建释放名额失败");
        waitlistTasks.createHoldTask(quota, order);

        return order;
    }
}
