package com.campus.ticket.service;

import com.campus.ticket.event.WaitlistOfferReadyEvent;
import com.campus.ticket.mapper.WaitlistOfferMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import com.campus.ticket.constants.RedisConstants;

@Slf4j
@Component
@Profile("async")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "campus.waitlist.jobs-enabled", havingValue = "true", matchIfMissing = true)
@SuppressWarnings("deprecation")
public class WaitlistDelayService
{
    private final RedissonClient redisson;
    private final WaitlistCoordinator coordinator;
    private final WaitlistOfferMapper offers;
    @org.springframework.beans.factory.annotation.Value("${campus.waitlist.delay-queue:" + RedisConstants.WAITLIST_DELAY_QUEUE + "}")
    private String queueName = RedisConstants.WAITLIST_DELAY_QUEUE;
    private RBlockingQueue<String> ready;
    private RDelayedQueue<String> delayed;

    @PostConstruct
    public void start()
    {
        ready = redisson.getBlockingQueue(queueName, StringCodec.INSTANCE);
        delayed = redisson.getDelayedQueue(ready);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void schedule(WaitlistOfferReadyEvent event)
    {
        try
        {
            long due = event.deadline().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            delayed.offer(event.offerId().toString(), Math.max(0, due - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        }
        catch (RuntimeException e)
        {
            // 订单/通知已经提交。数据库扫描负责补上未入队或取出后丢失的任务。
            log.warn("候补延迟任务入队失败，等待数据库扫描，offerId={}", event.offerId(), e);
        }
    }

    @Scheduled(fixedDelayString = "${campus.waitlist.delay-poll-ms:200}")
    public void poll()
    {
        for (int i = 0; i < 20; i++)
        {
            String id;
            try { id = ready.poll(); }
            catch (RuntimeException e) { log.warn("候补延迟队列暂不可用", e); return; }
            if (id == null) return;
            try
            {
                var offer = offers.findById(Long.valueOf(id));
                if (offer != null && "OFFERED".equals(offer.getStatus())) coordinator.progress(offer.getQuotaId());
            }
            catch (RuntimeException e) { log.warn("候补到期检查失败，由数据库扫描补偿，offerId={}", id, e); }
        }
    }
}
