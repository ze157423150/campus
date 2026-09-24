package com.campus.ticket.mapper;

import com.campus.ticket.dto.RegistrationDetail;
import com.campus.ticket.dto.RegistrationRosterItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface RegistrationQueryMapper {
    @Select("""
            <script>
            SELECT r.id AS registration_id, a.id AS activity_id,
                   a.title AS activity_title, a.status AS activity_status,
                   a.location, a.start_time, r.create_time AS registration_time,
                   r.status, r.cancel_time
            FROM registration r
            JOIN activity a ON a.id = r.activity_id
            WHERE r.user_id = #{userId}
            <if test="status != null">AND r.status = #{status}</if>
            ORDER BY r.id DESC
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<RegistrationDetail> findMine(@Param("userId") Long userId, @Param("status") String status,
                                      @Param("offset") long offset, @Param("pageSize") int pageSize);

    @Select("""
            <script>
            SELECT COUNT(*) FROM registration
            WHERE user_id = #{userId}
            <if test="status != null">AND status = #{status}</if>
            </script>
            """)
    long countMine(@Param("userId") Long userId, @Param("status") String status);

    @Select("""
            <script>
            SELECT r.id AS registration_id, r.user_id, u.student_no, u.name,
                   r.status, r.create_time AS registration_time, r.cancel_time
            FROM registration r
            JOIN campus_user u ON u.id = r.user_id
            WHERE r.activity_id = #{activityId}
            <if test="status != null">AND r.status = #{status}</if>
            ORDER BY r.id DESC
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<RegistrationRosterItem> findRoster(@Param("activityId") Long activityId,
                                           @Param("status") String status,
                                           @Param("offset") long offset, @Param("pageSize") int pageSize);

    @Select("""
            <script>
            SELECT COUNT(*) FROM registration
            WHERE activity_id = #{activityId}
            <if test="status != null">AND status = #{status}</if>
            </script>
            """)
    long countRoster(@Param("activityId") Long activityId, @Param("status") String status);
}
