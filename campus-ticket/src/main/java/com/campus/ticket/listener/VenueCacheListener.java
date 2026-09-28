package com.campus.ticket.listener;

import com.campus.ticket.cache.VenueCacheStore;
import com.campus.ticket.event.VenueChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class VenueCacheListener
{
    private final VenueCacheStore cacheStore;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(VenueChangedEvent event)
    {
        try
        {
            cacheStore.invalidate(event.venueId());
        }
        catch (RuntimeException e)
        {
            // 数据库已提交，不能伪装成写入回滚；当前由 TTL 限制旧缓存保留时间。
            log.error("场馆资料已提交，但缓存失效失败，venueId={}", event.venueId(), e);
        }
    }
}
