package com.campus.ticket.mapper;

import com.campus.ticket.entity.ActivityWaitlist;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ActivityWaitlistMapper
{
    @Select("""
            SELECT id, activity_id, user_id, status, create_time, update_time
            FROM activity_waitlist
            WHERE activity_id = #{activityId}
              AND user_id = #{userId}
              AND active_flag = 1
            """)
    ActivityWaitlist findActive(@Param("activityId") Long activityId, @Param("userId") Long userId);

    @Insert("""
            INSERT INTO activity_waitlist (activity_id, user_id, status)
            VALUES (#{activityId}, #{userId}, 'WAITING')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ActivityWaitlist waitlist);

    @Select("""
            SELECT id, activity_id, user_id, status, create_time, update_time
            FROM activity_waitlist
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    ActivityWaitlist findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM activity_waitlist
            WHERE activity_id = #{activityId}
              AND status = 'WAITING'
              AND id < #{id}
            """)
    long countWaitingAhead(@Param("activityId") Long activityId, @Param("id") Long id);

    @Update("""
        UPDATE activity_waitlist
        SET status = 'CANCELLED'
        WHERE id = #{id}
          AND activity_id = #{activityId}
          AND user_id = #{userId}
          AND status = 'WAITING'
        """)
    int cancelWaiting(@Param("id") Long id, @Param("activityId") Long activityId, @Param("userId") Long userId);

    @Select("""
        SELECT id, activity_id, user_id, status, create_time, update_time
        FROM activity_waitlist
        WHERE activity_id = #{activityId}
          AND status = 'WAITING'
        ORDER BY id
        LIMIT 1
        FOR UPDATE
        """)
    ActivityWaitlist lockFirstWaiting(@Param("activityId") Long activityId);

    @Update("""
        UPDATE activity_waitlist
        SET status = 'OFFERED'
        WHERE id = #{id}
          AND status = 'WAITING'
        """)
    int markOffered(@Param("id") Long id);
}