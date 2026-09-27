package com.campus.ticket.constants;

import java.time.Duration;

public final class RedisConstants {

    public static final String LOGIN_KEY_PREFIX = "campus:login:";
    public static final Duration LOGIN_TTL = Duration.ofDays(30);
    public static final String ACTIVITY_DETAIL_KEY_PREFIX = "campus:activity:published:detail:v3:";
    public static final Duration ACTIVITY_DETAIL_TTL = Duration.ofSeconds(30);
    public static final Duration ACTIVITY_DETAIL_TTL_JITTER = Duration.ofSeconds(10);
    public static final String ACTIVITY_CACHE_VERSION_KEY_PREFIX = "campus:activity:cache:version:";
    // 空字符串表示：已查询过数据库，活动不存在
    public static final String CACHE_NULL_VALUE = "";

    public static final Duration ACTIVITY_NULL_TTL = Duration.ofSeconds(10);

    // 活动详情缓存重建锁
    public static final String ACTIVITY_REBUILD_LOCK_PREFIX = "campus:lock:activity:rebuild:";

    // 获取缓存重建锁时，最多等待多久
    public static final Duration ACTIVITY_REBUILD_LOCK_WAIT = Duration.ofSeconds(2);
    public static final Duration ACTIVITY_DETAIL_PHYSICAL_TTL = Duration.ofMinutes(2);
    public static final String BOOKING_KEY_PREFIX = "campus:booking:";

    public static String bookingInventoryKey(Long activityId)
    {
        return BOOKING_KEY_PREFIX + "{" + activityId + "}:inventory";
    }

    public static String bookingRequestsKey(Long activityId)
    {
        return BOOKING_KEY_PREFIX + "{" + activityId + "}:requests";
    }

    public static String bookingPendingKey(Long activityId)
    {
        return BOOKING_KEY_PREFIX + "{" + activityId + "}:pending";
    }
    // 申请的业务处理时限，不是 Redis key 的过期时间
    public static final Duration BOOKING_PROCESSING_TIMEOUT = Duration.ofMinutes(2);
    private RedisConstants() {
    }

    public static final Duration BOOKING_DISPATCH_RETRY_INTERVAL = Duration.ofSeconds(30);

    public static final String RATE_LIMIT_SLIDING_KEY_PREFIX = "campus:rate-limit:sliding:";

    public static final String RATE_LIMIT_BUCKET_KEY_PREFIX = "campus:rate-limit:bucket:";
}
