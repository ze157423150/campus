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
public class ActivityRefreshDispatcher {

    private final ThreadPoolExecutor executor;

    // 记录本实例中正在排队或执行刷新的活动
    private final Set<Long> refreshing = ConcurrentHashMap.newKeySet();

    public ActivityRefreshDispatcher(@Qualifier("activityCacheExecutor") ThreadPoolExecutor executor) {
        this.executor = executor;
    }

    public void submit(Long activityId, Runnable refreshTask) {
        // add是原子操作：已有该ID，说明刷新已排队或正在执行
        if (!refreshing.add(activityId)) {
            return;
        }

        try {
            executor.execute(() -> {
                try {
                    refreshTask.run();
                } catch (Exception e) {
                    log.error(
                            "活动缓存后台刷新失败，activityId={}",
                            activityId,
                            e
                    );
                } finally {
                    refreshing.remove(activityId);
                }
            });
        } catch (RejectedExecutionException e) {
            // 没有提交成功，后台任务不会执行finally，需要这里清理
            refreshing.remove(activityId);

            log.debug(
                    "刷新任务未被接收，activityId={}",
                    activityId
            );
        }
    }
}