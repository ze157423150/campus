package com.campus.ticket.booking;

import com.campus.ticket.entity.Activity;
import com.campus.ticket.entity.Registration;
import com.campus.ticket.event.ActivityChangedEvent;
import com.campus.ticket.mapper.ActivityMapper;
import com.campus.ticket.mapper.RegistrationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class BookingConsumeService
{
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ActivityMapper activityMapper;
    private final RegistrationMapper registrationMapper;
    private final BookingMapper bookingMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public BookingConsumeResult  consume(BookingMessage message)
    {
        validateMessage(message);

        Long activityId = message.activityId();
        Long userId = message.userId();

        // 同一活动的数据库写入按顺序执行
        Activity activity = activityMapper.findByIdForUpdate(activityId);

        if (activity == null)
        {
            throw new IllegalStateException("申请对应的活动不存在");
        }



        // 先检查已处理结果，再检查时间和活动状态
        BookingOrder existingOrder = bookingMapper.lock(message.orderId());

        if (existingOrder != null)
        {
            if (!userId.equals(existingOrder.getUserId())
                    || !activityId.equals(existingOrder.getActivityId())
                    || !message.epoch().equals(existingOrder.getEpoch()))
            {
                throw new IllegalStateException("订单编号对应的申请信息不一致");
            }

            if (BookingOrderStatus.SUCCEEDED.equals(existingOrder.getStatus()))
            {
                return BookingConsumeResult.succeeded(existingOrder.getRegistrationId());
            }

            if (BookingOrderStatus.FAILED.equals(existingOrder.getStatus()))
            {
                return BookingConsumeResult.failed(existingOrder.getFailureCode());
            }

            if (BookingOrderStatus.CANCELLED.equals(existingOrder.getStatus()))
            {
                return BookingConsumeResult.cancelled(existingOrder.getRegistrationId());
            }

            throw new IllegalStateException("订单已有处理状态，不能重新执行报名");
        }

        if (!"PUBLISHED".equals(activity.getStatus()))
        {
            throw new IllegalStateException("活动当前不允许报名");
        }

        Instant acceptedAt = Instant.ofEpochMilli(message.acceptedAtMillis());
        Instant expiresAt = Instant.ofEpochMilli(message.expiresAtMillis());
        Instant registrationStart = activity.getRegistrationStartTime().atZone(BUSINESS_ZONE).toInstant();
        Instant registrationEnd = activity.getRegistrationEndTime().atZone(BUSINESS_ZONE).toInstant();

        // 检查受理申请时是否处于报名时间内
        if (acceptedAt.isBefore(registrationStart) || !acceptedAt.isBefore(registrationEnd))
        {
            throw new IllegalStateException("申请受理时间不在报名时间范围内");
        }

        if (!Instant.now().isBefore(expiresAt))
        {
            return recordExpired(message);
        }

        Registration registration = registrationMapper.findByUserAndActivityForUpdate(userId, activityId);

        if (registration != null
                && !"CANCELLED".equals(registration.getStatus()))
        {
            throw new IllegalStateException("用户已有有效报名或报名状态异常");
        }

        int deductedRows = activityMapper.deductQuota(activityId);

        if (deductedRows != 1)
        {
            throw new IllegalStateException("数据库名额不足或活动状态已变化");
        }

        Long registrationId;

        if (registration == null)
        {
            Registration newRegistration = new Registration();
            newRegistration.setUserId(userId);
            newRegistration.setActivityId(activityId);

            int insertedRows = registrationMapper.insert(newRegistration);

            if (insertedRows != 1 || newRegistration.getId() == null)
            {
                throw new IllegalStateException("创建报名记录失败");
            }

            registrationId = newRegistration.getId();
        }
        else
        {
            int restoredRows = registrationMapper.reactivate(registration.getId());

            if (restoredRows != 1)
            {
                throw new IllegalStateException("恢复报名记录失败");
            }

            registrationId = registration.getId();
        }

        BookingOrder order = new BookingOrder();
        order.setOrderId(message.orderId());
        order.setUserId(userId);
        order.setActivityId(activityId);
        order.setRequestKey(message.orderId());
        order.setEpoch(message.epoch());
        order.setRegistrationId(registrationId);
        order.setAcceptedAt(LocalDateTime.ofInstant(acceptedAt, BUSINESS_ZONE));
        order.setExpiresAt(LocalDateTime.ofInstant(expiresAt, BUSINESS_ZONE));

        int insertedOrders = bookingMapper.insertSucceeded(order);

        if (insertedOrders != 1)
        {
            throw new IllegalStateException("创建成功订单失败");
        }

        bookingMapper.log(
                message.orderId(),
                "SUCCEEDED",
                "报名落库成功，registrationId=" + registrationId
        );

        eventPublisher.publishEvent(new ActivityChangedEvent(activityId));

        return BookingConsumeResult.succeeded(registrationId);
    }

    private void validateMessage(BookingMessage message)
    {
        if (message == null
                || message.orderId() == null
                || message.orderId().isBlank()
                || message.orderId().length() > 36
                || message.userId() == null
                || message.userId() <= 0
                || message.activityId() == null
                || message.activityId() <= 0
                || message.epoch() == null
                || message.epoch() != 1L
                || !"PENDING".equals(message.status())
                || message.acceptedAtMillis() == null
                || message.expiresAtMillis() == null
                || message.acceptedAtMillis() <= 0
                || message.acceptedAtMillis() >= message.expiresAtMillis())
        {
            throw new IllegalArgumentException("报名消息内容不合法");
        }
    }
    private BookingConsumeResult recordExpired(BookingMessage message)
    {
        BookingOrder order = new BookingOrder();
        order.setOrderId(message.orderId());
        order.setUserId(message.userId());
        order.setActivityId(message.activityId());
        order.setRequestKey(message.orderId());
        order.setEpoch(message.epoch());
        order.setAcceptedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(message.acceptedAtMillis()), BUSINESS_ZONE));
        order.setExpiresAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(message.expiresAtMillis()), BUSINESS_ZONE));

        int insertedRows = bookingMapper.insertExpired(order);

        if (insertedRows != 1)
        {
            throw new IllegalStateException("记录超时订单失败");
        }

        bookingMapper.log(
                message.orderId(),
                "EXPIRED",
                "申请超过处理截止时间，等待归还Redis预占名额"
        );

        return BookingConsumeResult.failed("EXPIRED");
    }
}