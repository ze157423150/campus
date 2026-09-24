package com.campus.ticket.mapper;

import com.campus.ticket.dto.RegistrationDetail;
import com.campus.ticket.entity.Registration;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface RegistrationMapper {

    @Insert("""
            INSERT INTO registration (user_id, activity_id)
            VALUES (#{userId}, #{activityId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Registration registration);
    @Select("""
        SELECT
            r.id AS registration_id,
            a.id AS activity_id,
            a.title AS activity_title,
            a.status AS activity_status,
            a.location,
            a.start_time,
            r.create_time AS registration_time,
            r.status,
            r.cancel_time
        FROM registration r
        JOIN activity a ON r.activity_id = a.id
        WHERE r.user_id = #{userId}
        ORDER BY r.id DESC
        """)
    List<RegistrationDetail> findByUserId(@Param("userId") Long userId);

    @Select("""
        SELECT id, user_id, activity_id, create_time, status, cancel_time
        FROM registration
        WHERE id = #{registrationId}
          AND user_id = #{userId}
        """)
    Registration findByIdAndUserId(
            @Param("registrationId") Long registrationId,
            @Param("userId") Long userId
    );

    @Update("""
        UPDATE registration
        SET status = 'CANCELLED',
            cancel_time = NOW()
        WHERE id = #{registrationId}
          AND user_id = #{userId}
          AND status = 'REGISTERED'
        """)
    int cancel(
            @Param("registrationId") Long registrationId,
            @Param("userId") Long userId
    );
    @Select("""
        SELECT id, user_id, activity_id, create_time, status, cancel_time
        FROM registration
        WHERE user_id = #{userId}
          AND activity_id = #{activityId}
        FOR UPDATE
        """)
    Registration findByUserAndActivityForUpdate(
            @Param("userId") Long userId,
            @Param("activityId") Long activityId
    );
    @Update("""
        UPDATE registration
        SET status = 'REGISTERED',
            cancel_time = NULL
        WHERE id = #{registrationId}
          AND status = 'CANCELLED'
        """)
    int reactivate(@Param("registrationId") Long registrationId);

    @Update("""
        UPDATE registration
        SET status = 'CANCELLED',
            cancel_time = NOW()
        WHERE activity_id = #{activityId}
          AND status = 'REGISTERED'
        """)
    int cancelAllByActivityId(@Param("activityId") Long activityId);
}