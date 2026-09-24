package com.campus.ticket.constants;

import java.time.Duration;

public final class RedisConstants {

    public static final String LOGIN_KEY_PREFIX = "campus:login:";
    public static final Duration LOGIN_TTL = Duration.ofDays(30);
    public static final String ACTIVITY_DETAIL_KEY_PREFIX = "campus:activity:published:detail:v2:";
    public static final Duration ACTIVITY_DETAIL_TTL = Duration.ofSeconds(30);
    // 空字符串表示：已查询过数据库，活动不存在
    public static final String CACHE_NULL_VALUE = "";

    public static final Duration ACTIVITY_NULL_TTL = Duration.ofSeconds(10);


    private RedisConstants() {
    }
}
