package com.campus.ticket.mapper;

import com.campus.ticket.entity.Activity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ActivityMapper {

    @Select("""
            SELECT id, title, location,
                   start_time, end_time,
                   registration_start_time, registration_end_time,
                   total_quota, remaining_quota, create_time
            FROM activity
            WHERE id = #{id}
            """)
    Activity findById(@Param("id") Long id);

    @Select("""
        SELECT id, title, location,
               start_time, end_time,
               registration_start_time, registration_end_time,
               total_quota, remaining_quota, create_time
        FROM activity
        ORDER BY id DESC
        LIMIT #{pageSize} OFFSET #{offset}
        """)
    List<Activity> findPage(
            @Param("offset") long offset,
            @Param("pageSize") int pageSize
    );
    @Select("SELECT COUNT(*) FROM activity")
    long countAll();

    @Update("""
        UPDATE activity
        SET remaining_quota = remaining_quota - 1
        WHERE id = #{activityId}
          AND remaining_quota > 0
        """)
    int deductQuota(@Param("activityId") Long activityId);
    @Select("""
        SELECT id, title, location,
               start_time, end_time,
               registration_start_time, registration_end_time,
               total_quota, remaining_quota, create_time
        FROM activity
        WHERE id = #{activityId}
        FOR UPDATE
        """)
    Activity findByIdForUpdate(@Param("activityId") Long activityId);

    @Update("""
        UPDATE activity
        SET remaining_quota = remaining_quota + 1
        WHERE id = #{activityId}
          AND remaining_quota < total_quota
        """)
    int restoreQuota(@Param("activityId") Long activityId);
}