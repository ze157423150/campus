package com.campus.ticket.service;

import com.campus.ticket.mapper.WaitlistWorkflowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WaitlistCoordinator
{
    private final WaitlistWorkflowMapper db;
    private final WaitlistRedisTaskProcessor processor;
    private final WaitlistWorkflowService workflow;

    public void progress(Long quotaId)
    {
        // 不创建“空事务”的同步上下文，避免 MyBatis 一级缓存跨多轮数据库事务复用。
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("候补协调器必须在数据库事务提交后调用");
        // 限制单次工作量；未完成的部分由后台继续。
        for (int i = 0; i < 12; i++)
        {
            Long task = db.nextTask(quotaId);
            if (task != null)
            {
                if (!processor.tryProcess(task)) return;
                continue;
            }
            if (!workflow.advance(quotaId)) return;
        }
    }
}
