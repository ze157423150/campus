package com.campus.ticket.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.campus.ticket.service.BookingDispatchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Profile("async")
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "campus.booking.jobs-enabled",
        havingValue = "true"
)
public class BookingDispatchJob
{
    private final BookingMapper bookingMapper;
    private final BookingRedisStore bookingRedisStore;
    private final BookingDispatchService bookingDispatchService;

    private long activityCursor = 0L;

    @Scheduled(
            fixedDelayString = "${campus.booking.dispatch-delay-ms:1000}",
            initialDelayString = "${campus.booking.dispatch-initial-delay-ms:5000}"
    )
    public void dispatch()
    {
        List<Long> activityIds;

        try
        {
            activityIds = bookingMapper.findDispatchActivityIds(activityCursor);
        }
        catch (Exception e)
        {
            log.error("查询待检查活动失败", e);
            return;
        }

        if (activityIds.isEmpty())
        {
            activityCursor = 0L;
            return;
        }

        for (Long activityId : activityIds)
        {
            try
            {
                dispatchActivity(activityId);
            }
            catch (Exception e)
            {
                log.error("活动申请投递失败，activityId={}", activityId, e);
            }

            activityCursor = activityId;
        }
    }

    private void dispatchActivity(Long activityId)
    {
        // 每轮每个活动最多领取10条，避免一直处理同一个活动
        for (int i = 0; i < 10; i++)
        {
            BookingDispatchMessage message = bookingRedisStore.claimDueRequest(activityId);

            if (message == null)
            {
                return;
            }

            bookingDispatchService.send(activityId, message);
        }
    }
}
