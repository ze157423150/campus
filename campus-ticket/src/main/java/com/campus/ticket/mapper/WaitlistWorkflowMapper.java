package com.campus.ticket.mapper;

import com.campus.ticket.entity.*;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface WaitlistWorkflowMapper
{
    @Select("SELECT NOW(3)")
    LocalDateTime now();

    @Select("SELECT * FROM waitlist_quota WHERE id=#{id}")
    WaitlistQuota quota(Long id);

    @Select("SELECT * FROM activity_waitlist WHERE id=#{id} FOR UPDATE")
    ActivityWaitlist lockWaitlist(Long id);

    @Update("UPDATE activity_waitlist SET status=#{status} WHERE id=#{id}")
    int setWaitlist(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE activity_waitlist SET status='CANCELLED' WHERE activity_id=#{activityId} AND user_id=#{userId} AND status='WAITING'")
    int removeWaiting(@Param("activityId") Long activityId, @Param("userId") Long userId);

    @Update("UPDATE activity_waitlist SET status='CANCELLED' WHERE activity_id=#{activityId} AND status='WAITING'")
    int closeWaiting(Long activityId);

    @Update("UPDATE waitlist_quota SET status=#{status}, version=#{version} WHERE id=#{id} AND version=#{expected}")
    int move(@Param("id") Long id, @Param("expected") Long expected, @Param("version") Long version, @Param("status") String status);

    @Update("UPDATE waitlist_offer SET status=#{status}, close_reason=#{reason}, closed_at=NOW(3) WHERE id=#{id} AND status IN ('PREPARING','OFFERED')")
    int closeOffer(@Param("id") Long id, @Param("status") String status, @Param("reason") String reason);

    @Update("UPDATE waitlist_offer SET status='OFFERED', confirm_deadline=#{deadline} WHERE id=#{id} AND status='PREPARING'")
    int activate(@Param("id") Long id, @Param("deadline") LocalDateTime deadline);

    @Update("UPDATE waitlist_offer SET status='CONFIRMED', confirmed_at=NOW(3), confirmed_order_id=#{orderId} WHERE id=#{id} AND status='OFFERED' AND confirm_deadline>NOW(3)")
    int confirm(@Param("id") Long id, @Param("orderId") String orderId);

    @Select("SELECT COUNT(*) FROM waitlist_redis_task WHERE quota_id=#{id} AND status='PENDING'")
    long pending(Long id);

    @Select("SELECT result_code FROM waitlist_redis_task WHERE quota_id=#{id} AND quota_version=#{version} AND status='DONE'")
    String result(@Param("id") Long id, @Param("version") Long version);

    @Select("SELECT id FROM waitlist_quota WHERE id>#{cursor} AND status IN ('HELD','OFFERED') ORDER BY id LIMIT #{limit}")
    List<Long> activeQuotas(@Param("cursor") long cursor, @Param("limit") int limit);

    @Select("SELECT id FROM waitlist_redis_task WHERE quota_id=#{id} AND status='PENDING' ORDER BY quota_version LIMIT 1")
    Long nextTask(Long id);

    @Select("SELECT * FROM waitlist_offer WHERE waitlist_id=#{id}")
    WaitlistOffer byWaitlist(Long id);

    @Select("SELECT o.* FROM waitlist_offer o JOIN activity_waitlist w ON w.id=o.waitlist_id WHERE o.id=#{id} AND w.user_id=#{userId}")
    WaitlistOffer ownedOffer(@Param("id") Long id, @Param("userId") Long userId);

    @Insert("INSERT INTO waitlist_notification(user_id,activity_id,offer_id,content) VALUES(#{userId},#{activityId},#{offerId},#{content})")
    int notifyUser(@Param("userId") Long userId, @Param("activityId") Long activityId, @Param("offerId") Long offerId, @Param("content") String content);

    @Select("SELECT n.id,n.activity_id AS activityId,n.offer_id AS offerId,n.content,n.read_time AS readTime,n.create_time AS createTime,o.status AS offerStatus,o.confirm_deadline AS confirmDeadline FROM waitlist_notification n JOIN waitlist_offer o ON o.id=n.offer_id WHERE n.user_id=#{userId} AND n.id>#{afterId} ORDER BY n.id LIMIT #{limit}")
    List<Map<String,Object>> notifications(@Param("userId") Long userId, @Param("afterId") long afterId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM waitlist_notification WHERE id=#{id} AND user_id=#{userId}")
    int ownsNotice(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE waitlist_notification SET read_time=COALESCE(read_time,NOW(3)) WHERE id=#{id} AND user_id=#{userId}")
    void readNotice(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM waitlist_quota WHERE activity_id=#{id} AND status IN ('HELD','OFFERED')")
    long heldCount(Long id);

    @Select("SELECT DISTINCT w.activity_id FROM activity_waitlist w JOIN activity a ON a.id=w.activity_id WHERE w.status='WAITING' AND (a.status<>'PUBLISHED' OR a.registration_end_time<=NOW(3) OR a.start_time<=NOW(3)) ORDER BY w.activity_id LIMIT 50")
    List<Long> closedActivities();
}

