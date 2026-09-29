package com.campus.ticket.job;

import com.campus.ticket.mapper.*;
import com.campus.ticket.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("async")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "campus.waitlist.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class WaitlistRecoveryJob
{
    private final WaitlistRedisTaskMapper tasks;
    private final WaitlistWorkflowMapper db;
    private final WaitlistRedisTaskProcessor processor;
    private final WaitlistCoordinator coordinator;
    private final WaitlistWorkflowService workflow;
    private long cursor;

    @Scheduled(fixedDelayString = "${campus.waitlist.recovery-delay-ms:3000}", initialDelayString = "${campus.waitlist.recovery-initial-delay-ms:5000}")
    public void recover()
    {
        // 已 CONSUMED/RETURNED 的名额也可能还有待同步任务，因此任务必须独立扫描。
        for (Long id : tasks.findDueIds(50))
        {
            try { processor.tryProcess(id); }
            catch (RuntimeException e) { log.warn("候补任务保留重试，taskId={}", id, e); }
        }
        var ids = db.activeQuotas(cursor, 50);
        if (ids.isEmpty()) cursor = 0;
        for (Long id : ids)
        {
            cursor = id;
            try { coordinator.progress(id); }
            catch (RuntimeException e) { log.warn("候补流程稍后继续，quotaId={}", id, e); }
        }
        for (Long activityId : db.closedActivities())
        {
            try { workflow.closeWaitingForActivity(activityId); }
            catch (RuntimeException e) { log.warn("清理已结束活动候补失败，activityId={}", activityId, e); }
        }
    }
}

