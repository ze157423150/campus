package com.campus.ticket.booking;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface BookingMapper
{
    @Insert("INSERT INTO booking_order(order_id,user_id,activity_id,request_key,epoch,expires_at) VALUES(#{orderId},#{userId},#{activityId},#{requestKey},#{epoch},#{expiresAt})")
    int insert(BookingOrder order);

    @Select("SELECT * FROM booking_order WHERE order_id=#{id}")
    BookingOrder find(String id);

    @Select("SELECT * FROM booking_order WHERE order_id=#{id} FOR UPDATE")
    BookingOrder lock(String id);

    @Select("SELECT * FROM booking_order WHERE user_id=#{userId} AND request_key=#{key}")
    BookingOrder findRequest(@Param("userId") Long userId, @Param("key") String key);

    @Select("SELECT epoch FROM booking_inventory WHERE activity_id=#{id}")
    Long epoch(Long id);

    @Insert("INSERT INTO booking_inventory(activity_id,epoch) VALUES(#{id},1) ON DUPLICATE KEY UPDATE epoch=epoch+1,update_time=NOW(3)")
    int bumpEpoch(Long id);

    @Update("UPDATE booking_order SET status=#{status},registration_id=#{registrationId},accepted_at=#{acceptedAt},failure_code=#{failureCode},consume_failures=#{consumeFailures},redis_dirty=#{redisDirty},update_time=NOW(3) WHERE order_id=#{orderId}")
    int update(BookingOrder order);

    @Insert("INSERT INTO booking_order_log(order_id,event_type,detail) VALUES(#{id},#{type},#{detail})")
    void log(@Param("id") String id, @Param("type") String type, @Param("detail") String detail);

    @Select("SELECT id,event_type,detail,create_time FROM booking_order_log WHERE order_id=#{id} ORDER BY id DESC LIMIT 100")
    List<Map<String,Object>> logs(String id);

    @Insert("INSERT IGNORE INTO booking_outbox(order_id,activity_id) VALUES(#{id},#{activityId})")
    void ensureOutbox(@Param("id") String id, @Param("activityId") Long activityId);

    @Delete("DELETE FROM booking_outbox WHERE order_id=#{id}")
    void deleteOutbox(String id);

    @Select("SELECT order_id FROM booking_outbox WHERE next_attempt_time<=NOW(3) ORDER BY next_attempt_time LIMIT 1 FOR UPDATE SKIP LOCKED")
    String lockDueOutbox();

    @Update("UPDATE booking_outbox SET attempts=attempts+1,next_attempt_time=DATE_ADD(NOW(3), INTERVAL 15 SECOND),last_error=#{error} WHERE order_id=#{id}")
    void sent(@Param("id") String id, @Param("error") String error);

    @Select("SELECT order_id FROM booking_order WHERE (status IN ('NEW','PENDING') OR redis_dirty=1) AND next_check_time<=NOW(3) ORDER BY next_check_time LIMIT 100")
    List<String> dueOrders();

    @Update("UPDATE booking_order SET next_check_time=DATE_ADD(NOW(3), INTERVAL 5 SECOND) WHERE order_id=#{id}")
    void defer(String id);

    @Update("UPDATE booking_order SET redis_dirty=0 WHERE order_id=#{id} AND status IN ('FAILED','CANCELLED')")
    void clean(String id);

    @Select("""
        SELECT order_id
        FROM booking_order
        WHERE redis_dirty = 1
          AND status IN ('FAILED', 'CANCELLED')
          AND next_check_time <= NOW(3)
        ORDER BY next_check_time, order_id
        LIMIT #{batchSize}
        """)
    List<String> findDueRedisSyncOrders(@Param("batchSize") int batchSize);

    @Update("""
        UPDATE booking_order
        SET next_check_time = TIMESTAMPADD(SECOND, #{retrySeconds}, NOW(3))
        WHERE order_id = #{orderId}
          AND redis_dirty = 1
          AND status IN ('FAILED', 'CANCELLED')
          AND next_check_time <= NOW(3)
        """)
    int claimRedisSync(@Param("orderId") String orderId, @Param("retrySeconds") int retrySeconds);

    @Select("SELECT * FROM booking_order WHERE activity_id=#{id} AND status IN ('NEW','PENDING') FOR UPDATE")
    List<BookingOrder> activeOrders(Long id);

    @Select("SELECT * FROM booking_order WHERE registration_id=#{id} AND status='SUCCEEDED' FOR UPDATE")
    List<BookingOrder> registrationOrders(Long id);

    @Select("SELECT * FROM booking_order WHERE activity_id=#{id} AND status IN ('NEW','PENDING','SUCCEEDED') FOR UPDATE")
    List<BookingOrder> cancellableOrders(Long id);

    @Select("SELECT r.user_id AS userId, COALESCE(o.order_id,CONCAT('legacy-',r.id)) AS owner FROM registration r LEFT JOIN booking_order o ON o.registration_id=r.id AND o.status='SUCCEEDED' WHERE r.activity_id=#{id} AND r.status='REGISTERED'")
    List<Map<String,Object>> owners(Long id);

    @Select("SELECT activity_id FROM booking_inventory WHERE activity_id>#{cursor} ORDER BY activity_id LIMIT 20")
    List<Long> inventoryIds(long cursor);

    @Select("SELECT COUNT(*) FROM registration WHERE activity_id=#{id} AND status='REGISTERED'")
    long registered(Long id);

    @Select("SELECT COUNT(*) FROM booking_order WHERE activity_id=#{id} AND status IN ('NEW','PENDING')")
    long pending(Long id);

    @Select("""
        SELECT epoch
        FROM booking_inventory
        WHERE activity_id = #{activityId}
        FOR UPDATE
        """)
    Long findEpochForUpdate(@Param("activityId") Long activityId);
    @Select("""
        SELECT id
        FROM activity
        WHERE id > #{cursor}
        ORDER BY id
        LIMIT 20
        """)
    List<Long> findDispatchActivityIds(@Param("cursor") long cursor);

    @Insert("""
        INSERT INTO booking_order (
            order_id, user_id, activity_id, request_key,
            epoch, status, registration_id, accepted_at, expires_at
        )
        VALUES (
            #{orderId}, #{userId}, #{activityId}, #{requestKey},
            #{epoch}, 'SUCCEEDED', #{registrationId}, #{acceptedAt}, #{expiresAt}
        )
        """)
    int insertSucceeded(BookingOrder order);

    @Insert("""
        INSERT INTO booking_order (
            order_id, user_id, activity_id, request_key,
            epoch, status, accepted_at, expires_at,
            failure_code, redis_dirty
        )
        VALUES (
            #{orderId}, #{userId}, #{activityId}, #{requestKey},
            #{epoch}, 'FAILED', #{acceptedAt}, #{expiresAt},
            'EXPIRED', 1
        )
        """)
    int insertExpired(BookingOrder order);

    @Update("""
        UPDATE booking_order
        SET status = 'CANCELLED',
            redis_dirty = 1,
            next_check_time = NOW(3),
            update_time = NOW(3)
        WHERE order_id = #{orderId}
          AND status = 'SUCCEEDED'
        """)
    int cancelSucceeded(@Param("orderId") String orderId);
}
