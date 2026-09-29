package com.campus.ticket.mapper;

import com.campus.ticket.entity.WaitlistRedisTask;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface WaitlistRedisTaskMapper
{
    @Insert("""
            INSERT INTO waitlist_redis_task (
                quota_id,
                quota_version,
                operation_type,
                payload
            )
            VALUES (
                #{quotaId},
                #{quotaVersion},
                #{operationType},
                #{payload}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(WaitlistRedisTask task);

    @Select("""
            SELECT id, quota_id, quota_version, operation_type, payload,
                   status, attempts, next_attempt_time, create_time, update_time
            FROM waitlist_redis_task
            WHERE id = #{id}
            """)
    WaitlistRedisTask findById(@Param("id") Long id);

    @Select("""
            SELECT t.id
            FROM waitlist_redis_task t
            WHERE t.status = 'PENDING'
              AND t.next_attempt_time <= NOW(3)
              AND NOT EXISTS (
                  SELECT 1
                  FROM waitlist_redis_task previous_task
                  WHERE previous_task.quota_id = t.quota_id
                    AND previous_task.quota_version < t.quota_version
                    AND previous_task.status = 'PENDING'
              )
            ORDER BY t.next_attempt_time, t.id
            LIMIT #{batchSize}
            """)
    List<Long> findDueIds(@Param("batchSize") int batchSize);

    @Update("""
            UPDATE waitlist_redis_task
            SET attempts = attempts + 1,
                next_attempt_time = TIMESTAMPADD(SECOND, #{retrySeconds}, NOW(3))
            WHERE id = #{id}
              AND status = 'PENDING'
              AND next_attempt_time <= NOW(3)
            """)
    int claim(@Param("id") Long id, @Param("retrySeconds") int retrySeconds);

    @Update("""
            UPDATE waitlist_redis_task
            SET status = 'DONE', result_code = #{result}
            WHERE id = #{id}
              AND status = 'PENDING'
            """)
    int markDone(@Param("id") Long id, @Param("result") String result);
    @Select("""
        SELECT COUNT(*)
        FROM waitlist_redis_task
        WHERE quota_id = #{quotaId}
          AND quota_version < #{quotaVersion}
          AND status = 'PENDING'
        """)
    long countEarlierPending(@Param("quotaId") Long quotaId, @Param("quotaVersion") Long quotaVersion);
}
