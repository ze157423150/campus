package com.campus.ticket.job;

import com.campus.ticket.booking.BookingMapper;
import com.campus.ticket.service.BookingRedisSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Profile("async")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "campus.booking.redis-sync-enabled", havingValue = "true", matchIfMissing = true)
public class BookingRedisSyncJob
{
    private final BookingMapper bookingMapper;
    private final BookingRedisSyncService bookingRedisSyncService;

    @Value("${campus.booking.redis-sync-batch-size:50}")
    private int batchSize = 50;

    @Value("${campus.booking.redis-sync-retry-seconds:30}")
    private int retrySeconds = 30;

    @Scheduled(
            fixedDelayString = "${campus.booking.redis-sync-delay-ms:5000}",
            initialDelayString = "${campus.booking.redis-sync-initial-delay-ms:5000}"
    )
    public void synchronizeDueOrders()
    {
        List<String> orderIds;

        try
        {
            orderIds = bookingMapper.findDueRedisSyncOrders(Math.max(1, Math.min(batchSize, 200)));
        }
        catch (RuntimeException e)
        {
            log.error("查询待同步Redis订单失败，下一轮继续尝试", e);
            return;
        }

        for (String orderId : orderIds)
        {
            try
            {
                // 独立SQL提交领取结果；进程退出后，时间到达即可重新领取。
                int claimed = bookingMapper.claimRedisSync(orderId, Math.max(1, retrySeconds));

                if (claimed != 1)
                {
                    continue;
                }

                bookingRedisSyncService.synchronize(orderId);
                log.info("订单Redis后台同步完成，orderId={}", orderId);
            }
            catch (RuntimeException e)
            {
                // 领取时已经推迟下次尝试时间。失败不清除redis_dirty，也不阻塞下一订单。
                log.warn("订单Redis后台同步未完成，保留标记稍后重试，orderId={}", orderId, e);
            }
        }
    }
}
