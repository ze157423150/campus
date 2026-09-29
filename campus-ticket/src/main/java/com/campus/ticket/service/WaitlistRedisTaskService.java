package com.campus.ticket.service;

import com.campus.ticket.booking.BookingOrder;
import com.campus.ticket.booking.BookingOrderStatus;
import com.campus.ticket.constants.WaitlistQuotaStatus;
import com.campus.ticket.constants.WaitlistRedisTaskConstants;
import com.campus.ticket.dto.WaitlistHoldPayload;
import com.campus.ticket.entity.WaitlistQuota;
import com.campus.ticket.entity.WaitlistRedisTask;
import com.campus.ticket.mapper.WaitlistRedisTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.campus.ticket.dto.WaitlistReturnPayload;

@Service
@RequiredArgsConstructor
public class WaitlistRedisTaskService
{
    private final WaitlistRedisTaskMapper taskMapper;
    private final JsonMapper jsonMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void createTransition(WaitlistQuota quota, String operation, com.campus.ticket.dto.WaitlistTransitionPayload payload)
    {
        if (quota == null || quota.getId() == null || quota.getVersion() == null || quota.getVersion() <= 0
                || operation == null || !java.util.Set.of("OFFER", "RELEASE", "CONFIRM").contains(operation)
                || payload == null || !java.util.Objects.equals(quota.getActivityId(), payload.activityId())
                || payload.offerId() == null || payload.userId() == null || payload.epoch() == null)
            throw new IllegalArgumentException("候补流转任务参数不完整");
        WaitlistRedisTask task = new WaitlistRedisTask();
        task.setQuotaId(quota.getId());
        task.setQuotaVersion(quota.getVersion());
        task.setOperationType(operation);
        task.setPayload(jsonMapper.writeValueAsString(payload));
        if (taskMapper.insert(task) != 1) throw new IllegalStateException("创建候补同步任务失败");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long createHoldTask(WaitlistQuota quota, BookingOrder order)
    {
        if (quota == null || quota.getId() == null || quota.getId() <= 0 || order == null)
        {
            throw new IllegalArgumentException("创建名额接管任务的参数不合法");
        }

        if (!WaitlistQuotaStatus.HELD.equals(quota.getStatus())
                || !Long.valueOf(0L).equals(quota.getVersion()))
        {
            throw new IllegalStateException("只能为初始保留状态的名额创建接管任务");
        }

        if (!BookingOrderStatus.CANCELLED.equals(order.getStatus()))
        {
            throw new IllegalStateException("原报名订单尚未取消");
        }

        if (quota.getActivityId() == null
                || !quota.getActivityId().equals(order.getActivityId())
                || quota.getSourceOrderId() == null
                || !quota.getSourceOrderId().equals(order.getOrderId()))
        {
            throw new IllegalStateException("名额来源与取消订单不一致");
        }

        if (order.getUserId() == null || order.getUserId() <= 0
                || order.getRegistrationId() == null || order.getRegistrationId() <= 0
                || order.getEpoch() == null || order.getEpoch() <= 0)
        {
            throw new IllegalStateException("取消订单的报名信息不完整");
        }

        WaitlistHoldPayload payload = new WaitlistHoldPayload(
                order.getActivityId(),
                order.getOrderId(),
                order.getUserId(),
                order.getRegistrationId(),
                order.getEpoch()
        );

        WaitlistRedisTask task = new WaitlistRedisTask();
        task.setQuotaId(quota.getId());
        task.setQuotaVersion(0L);
        task.setOperationType(WaitlistRedisTaskConstants.HOLD);
        task.setPayload(jsonMapper.writeValueAsString(payload));

        int inserted = taskMapper.insert(task);

        if (inserted != 1 || task.getId() == null)
        {
            throw new IllegalStateException("创建Redis名额接管任务失败");
        }

        return task.getId();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long createReturnTask(WaitlistQuota quota, Long epoch)
    {
        if (quota == null || quota.getId() == null || quota.getId() <= 0
                || quota.getActivityId() == null || quota.getActivityId() <= 0
                || quota.getSourceOrderId() == null || quota.getSourceOrderId().isBlank()
                || epoch == null || epoch <= 0)
        {
            throw new IllegalArgumentException("创建名额归还任务的参数不合法");
        }

        if (!WaitlistQuotaStatus.RETURNED.equals(quota.getStatus())
                || quota.getVersion() == null || quota.getVersion() <= 0)
        {
            throw new IllegalStateException("名额尚未完成数据库归还状态变更");
        }

        WaitlistReturnPayload payload = new WaitlistReturnPayload(
                quota.getActivityId(),
                quota.getSourceOrderId(),
                epoch
        );

        WaitlistRedisTask task = new WaitlistRedisTask();
        task.setQuotaId(quota.getId());
        task.setQuotaVersion(quota.getVersion());
        task.setOperationType(WaitlistRedisTaskConstants.RETURN);
        task.setPayload(jsonMapper.writeValueAsString(payload));

        int inserted = taskMapper.insert(task);

        if (inserted != 1 || task.getId() == null)
        {
            throw new IllegalStateException("创建Redis名额归还任务失败");
        }

        return task.getId();
    }
}
