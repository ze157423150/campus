package com.campus.ticket.mapper;

import com.campus.ticket.entity.WaitlistQuota;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WaitlistQuotaMapper
{
    @Insert("""
            INSERT INTO waitlist_quota (
                activity_id, source_order_id, status, version
            )
            VALUES (
                #{activityId}, #{sourceOrderId}, 'HELD', 0
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(WaitlistQuota quota);

    @Select("""
            SELECT id, activity_id, source_order_id, status,
                   current_offer_id, version, create_time, update_time
            FROM waitlist_quota
            WHERE source_order_id = #{sourceOrderId}
            """)
    WaitlistQuota findBySourceOrderId(@Param("sourceOrderId") String sourceOrderId);

    @Select("""
            SELECT id, activity_id, source_order_id, status,
                   current_offer_id, version, create_time, update_time
            FROM waitlist_quota
            WHERE id = #{id}
            FOR UPDATE
            """)
    WaitlistQuota lockById(@Param("id") Long id);

    @Update("""
        UPDATE waitlist_quota
        SET status = 'OFFERED',
            current_offer_id = #{offerId},
            version = version + 1
        WHERE id = #{quotaId}
          AND status = 'HELD'
          AND version = #{expectedVersion}
        """)
    int markOffered(@Param("quotaId") Long quotaId, @Param("offerId") Long offerId, @Param("expectedVersion") Long expectedVersion);
}