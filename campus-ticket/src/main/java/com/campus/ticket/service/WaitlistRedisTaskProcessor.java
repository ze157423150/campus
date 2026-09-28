package com.campus.ticket.service;

import com.campus.ticket.constants.WaitlistRedisTaskConstants;
import com.campus.ticket.dto.WaitlistHoldPayload;
import com.campus.ticket.entity.WaitlistRedisTask;
import com.campus.ticket.mapper.WaitlistRedisTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class WaitlistRedisTaskProcessor
{
    private final WaitlistRedisTaskMapper taskMapper;
    private final WaitlistRedisService waitlistRedisService;
    private final JsonMapper jsonMapper;

    @Value("${campus.waitlist.redis-task.retry-seconds:30}")
    private int retrySeconds;

    @Transactional(propagation = Propagation.NEVER)
    public boolean tryProcess(Long taskId)
    {
        if (taskId == null || taskId <= 0)
        {
            throw new IllegalArgumentException("同步任务ID不合法");
        }

        WaitlistRedisTask task = taskMapper.findById(taskId);

        if (task == null)
        {
            throw new IllegalStateException("Redis同步任务不存在，taskId=" + taskId);
        }

        if (WaitlistRedisTaskConstants.DONE.equals(task.getStatus()))
        {
            return true;
        }

        if (!WaitlistRedisTaskConstants.PENDING.equals(task.getStatus()))
        {
            throw new IllegalStateException("Redis同步任务状态异常，taskId=" + taskId);
        }

        if (!WaitlistRedisTaskConstants.HOLD.equals(task.getOperationType())
                || !Long.valueOf(0L).equals(task.getQuotaVersion()))
        {
            throw new IllegalStateException("当前执行器不支持该任务类型或版本，taskId=" + taskId);
        }

        if (taskMapper.countEarlierPending(task.getQuotaId(), task.getQuotaVersion()) > 0)
        {
            return false;
        }

        int claimed = taskMapper.claim(taskId, Math.max(1, retrySeconds));

        if (claimed != 1)
        {
            return false;
        }

        WaitlistHoldPayload payload = jsonMapper.readValue(task.getPayload(), WaitlistHoldPayload.class);

        waitlistRedisService.hold(task.getId(), task.getQuotaId(), payload);

        int completed = taskMapper.markDone(taskId);

        if (completed == 1)
        {
            return true;
        }

        // 领取间隔到期后可能发生重复执行，另一个实例可能已完成标记。
        WaitlistRedisTask latest = taskMapper.findById(taskId);

        if (latest != null && WaitlistRedisTaskConstants.DONE.equals(latest.getStatus()))
        {
            return true;
        }

        throw new IllegalStateException("Redis已处理，但任务完成状态未确认，taskId=" + taskId);
    }
}