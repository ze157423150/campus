package com.campus.ticket.listener;

import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.event.ActivityChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityCacheListener {

    private final StringRedisTemplate stringRedisTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleActivityChanged(ActivityChangedEvent event) {
        String key =
                RedisConstants.ACTIVITY_DETAIL_KEY_PREFIX + event.activityId();

        try {
            stringRedisTemplate.delete(key);
        } catch (DataAccessException e) {
            log.error(
                    "活动详情缓存删除失败，activityId={}",
                    event.activityId(),
                    e
            );
        }
    }
}