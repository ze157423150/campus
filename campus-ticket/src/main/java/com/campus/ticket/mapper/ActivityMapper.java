package com.campus.ticket.mapper;

import com.campus.ticket.entity.Activity;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface ActivityMapper {

    @Select("""
            SELECT id, title, category, location,
                   start_time, end_time,
                   registration_start_time, registration_end_time,
                   total_quota, remaining_quota, create_time, status
            FROM activity
            WHERE id = #{id} AND status = 'PUBLISHED'
            """)
    Activity findById(@Param("id") Long id);

    // 两个查询共用筛选条件，并由 Service 传入同一个 now。
    String PUBLIC_FILTER = """
        FROM activity WHERE status = 'PUBLISHED'
        <if test="keyword != null">AND LOCATE(#{keyword}, title) > 0</if>
        <if test="category != null">AND category = #{category}</if>
        <choose>
            <when test="registrationPhase == 'NOT_STARTED'">
                AND registration_start_time &gt; #{now}
            </when>
            <when test="registrationPhase == 'OPEN'">
                AND registration_start_time &lt;= #{now}
                AND registration_end_time &gt; #{now}
            </when>
            <when test="registrationPhase == 'CLOSED'">
                AND registration_end_time &lt;= #{now}
            </when>
        </choose>
        """;

    @Select("<script>SELECT id, title, category, location, start_time, end_time, "
            + "registration_start_time, registration_end_time, total_quota, remaining_quota, create_time, status "
            + PUBLIC_FILTER + " ORDER BY id DESC LIMIT #{pageSize} OFFSET #{offset}</script>")
    List<Activity> findPage(@Param("keyword") String keyword,
                            @Param("category") String category,
                            @Param("registrationPhase") String registrationPhase,
                            @Param("now") LocalDateTime now,
                            @Param("offset") long offset,
                            @Param("pageSize") int pageSize);

    @Select("<script>SELECT COUNT(*) " + PUBLIC_FILTER + "</script>")
    long countAll(@Param("keyword") String keyword,
                  @Param("category") String category,
                  @Param("registrationPhase") String registrationPhase,
                  @Param("now") LocalDateTime now);

    @Update("""
        UPDATE activity
        SET remaining_quota = remaining_quota - 1
        WHERE id = #{activityId}
          AND status = 'PUBLISHED'
          AND remaining_quota > 0
        """)
    int deductQuota(@Param("activityId") Long activityId);

    @Select("""
        SELECT id, title, category, location,
               start_time, end_time,
               registration_start_time, registration_end_time,
               total_quota, remaining_quota, create_time, status
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

    @Insert("""
        insert into activity(title, category, location,
                             start_time, end_time, 
                             registration_start_time, registration_end_time, 
                             total_quota, remaining_quota,status) 
        values (
                #{title}, #{category}, #{location},
                #{startTime}, #{endTime},
                #{registrationStartTime}, #{registrationEndTime},
                #{totalQuota}, #{remainingQuota}, #{status}
        )
        """)
        @Options(useGeneratedKeys = true, keyProperty = "id")
        int insert(Activity activity);

    @Select("""
        SELECT id, title, category, location,
               start_time, end_time,
               registration_start_time, registration_end_time,
               total_quota, remaining_quota, create_time, status
        FROM activity
        WHERE id = #{id}
        """)
    Activity findManagementById(@Param("id") Long id);

    @Update("""
        UPDATE activity
        SET status = 'PUBLISHED'
        WHERE id = #{id}
          AND status = 'DRAFT'
        """)
    int publish(@Param("id") Long id);

    @Update("""
        UPDATE activity
        SET title = #{title},
            category = #{category},
            location = #{location},
            start_time = #{startTime},
            end_time = #{endTime},
            registration_start_time = #{registrationStartTime},
            registration_end_time = #{registrationEndTime},
            total_quota = #{totalQuota},
            remaining_quota = #{remainingQuota}
        WHERE id = #{id}
          AND status = 'DRAFT'
        """)
    int updateDraft(Activity activity);

    @Select("""
        <script>
        SELECT id, title, category, location,
               start_time, end_time,
               registration_start_time, registration_end_time,
               total_quota, remaining_quota, create_time, status
        FROM activity
        <where>
            <if test="status != null">
                status = #{status}
            </if>
        </where>
        ORDER BY id DESC
        LIMIT #{pageSize} OFFSET #{offset}
        </script>
        """)
    List<Activity> findManagementPage(
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("pageSize") int pageSize
    );
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM activity 
        <where>
            <if test="status != null">
                status = #{status}
            </if>
        </where>
        </script>
        """)
    long countManagement(@Param("status") String status);

    @Update("""
        UPDATE activity
        SET status = 'CANCELLED',
            remaining_quota = total_quota
        WHERE id = #{activityId}
          AND status IN ('DRAFT', 'PUBLISHED')
        """)
    int cancelActivity(@Param("activityId") Long activityId);

    @Select("""
        SELECT id
        FROM activity
        WHERE id > #{lastId}
        ORDER BY id
        LIMIT #{batchSize}
        """)
    List<Long> findIdsForBloom(@Param("lastId") Long lastId, @Param("batchSize") int batchSize);

}
