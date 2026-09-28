package com.campus.ticket.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Component
public class VenueRefreshDispatcher {

    private final ThreadPoolExecutor executor;

    // 记录本实例中正在排队或执行刷新的场馆
    private final Set<Long> refreshing = ConcurrentHashMap.newKeySet();

    public VenueRefreshDispatcher(@Qualifier("venueCacheExecutor") ThreadPoolExecutor executor) {
        this.executor = executor;
    }

    public void submit(Long venueId, Runnable refreshTask) {
        // add是原子操作：已有该ID，说明刷新已排队或正在执行
        if (!refreshing.add(venueId)) {
            return;
        }

        try {
            executor.execute(() -> {
                try {
                    refreshTask.run();
                } catch (Exception e) {
                    log.error(
                            "场馆缓存后台刷新失败，venueId={}",
                            venueId,
                            e
                    );
                } finally {
                    refreshing.remove(venueId);
                }
            });
        } catch (RejectedExecutionException e) {
            // 没有提交成功，后台任务不会执行finally，需要这里清理
            refreshing.remove(venueId);

            log.debug(
                    "刷新任务未被接收，venueId={}",
                    venueId
            );
        }
    }
}
