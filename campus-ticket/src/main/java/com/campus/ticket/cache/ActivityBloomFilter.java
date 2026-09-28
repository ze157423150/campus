package com.campus.ticket.cache;

import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.ActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityBloomFilter implements ApplicationRunner
{
    private final RedissonClient redissonClient;
    private final ActivityMapper activityMapper;

    @Value("${campus.cache.activity-bloom.expected-insertions:100000}")
    private long expectedInsertions;

    @Value("${campus.cache.activity-bloom.false-probability:0.001}")
    private double falseProbability;

    @Value("${campus.cache.activity-bloom.load-batch-size:500}")
    private int loadBatchSize;

    private volatile boolean initialLoadCompleted = false;

    private volatile boolean queryBypass = false;

    @Override
    public void run(ApplicationArguments args)
    {
        if (expectedInsertions <= 0 || loadBatchSize <= 0)
        {
            throw new IllegalArgumentException("布隆过滤器容量和加载批量必须大于零");
        }

        if (!Double.isFinite(falseProbability) || falseProbability <= 0 || falseProbability >= 1)
        {
            throw new IllegalArgumentException("布隆过滤器误判率必须在0和1之间");
        }

        initialLoadCompleted = false;

        try
        {
            RBloomFilter<String> bloomFilter = getFilter();
            boolean created = bloomFilter.tryInit(expectedInsertions, falseProbability);

            long lastId = 0L;
            long loadedCount = 0L;

            while (true)
            {
                List<Long> activityIds = activityMapper.findIdsForBloom(lastId, loadBatchSize);

                if (activityIds.isEmpty())
                {
                    break;
                }

                for (Long activityId : activityIds)
                {
                    bloomFilter.add(activityId.toString());
                }

                loadedCount += activityIds.size();
                lastId = activityIds.get(activityIds.size() - 1);
            }

            initialLoadCompleted = true;

            log.info("活动布隆过滤器初始加载完成，created={}, scannedIds={}", created, loadedCount);
        }
        catch (RuntimeException e)
        {
            log.error("活动布隆过滤器初始加载失败，不能据此拦截活动查询", e);
        }
    }

    public boolean isInitialLoadCompleted()
    {
        return initialLoadCompleted;
    }

    private RBloomFilter<String> getFilter()
    {
        return redissonClient.getBloomFilter(RedisConstants.ACTIVITY_BLOOM_KEY, StringCodec.INSTANCE);
    }

    public void add(Long activityId)
    {
        if (activityId == null || activityId <= 0)
        {
            throw new IllegalArgumentException("活动ID必须为正数");
        }

        if (!initialLoadCompleted)
        {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ACTIVITY_BLOOM_NOT_READY",
                    "活动索引尚未准备好，请稍后重试"
            );
        }

        try
        {
            getFilter().add(activityId.toString());
        }
        catch (RuntimeException e)
        {
            log.error("新增活动ID写入布隆过滤器失败，activityId={}", activityId, e);

            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ACTIVITY_BLOOM_WRITE_FAILED",
                    "活动创建暂未完成，请稍后重试",
                    e
            );
        }
    }
    public boolean mightContain(Long activityId)
    {
        if (activityId == null || activityId <= 0)
        {
            throw new IllegalArgumentException("活动ID必须为正数");
        }

        // 未完成初始化，或者已进入降级状态，继续原来的查询流程
        if (!initialLoadCompleted || queryBypass)
        {
            return true;
        }

        try
        {
            RBloomFilter<String> bloomFilter = getFilter();
            boolean mightExist = bloomFilter.contains(activityId.toString());

            if (mightExist)
            {
                return true;
            }

            // 阴性结果可能来自过滤器数据缺失，不能直接当成活动不存在
            if (!bloomFilter.isExists())
            {
                queryBypass = true;
                log.warn("活动布隆过滤器数据缺失，当前实例停止使用布隆过滤器拦截查询");
                return true;
            }

            // 再读取配置，配置缺失或异常时由下面的 catch 降级
            bloomFilter.getSize();

            return false;
        }
        catch (RuntimeException e)
        {
            queryBypass = true;
            log.error("活动布隆过滤器检查异常，当前实例停止使用布隆过滤器拦截查询", e);
            return true;
        }
    }
}