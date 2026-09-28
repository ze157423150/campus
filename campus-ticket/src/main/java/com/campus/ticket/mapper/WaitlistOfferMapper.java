package com.campus.ticket.mapper;

import com.campus.ticket.entity.WaitlistOffer;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WaitlistOfferMapper
{
    @Insert("""
            INSERT INTO waitlist_offer (
                waitlist_id,
                quota_id,
                source_order_id,
                status,
                confirm_deadline
            )
            VALUES (
                #{waitlistId},
                #{quotaId},
                #{sourceOrderId},
                'OFFERED',
                #{confirmDeadline}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(WaitlistOffer offer);

    @Select("""
            SELECT id, waitlist_id, quota_id, source_order_id, status,
                   confirm_deadline, confirmed_at, closed_at, close_reason,
                   confirmed_order_id, create_time, update_time
            FROM waitlist_offer
            WHERE id = #{id}
            """)
    WaitlistOffer findById(@Param("id") Long id);

    @Select("""
            SELECT id, waitlist_id, quota_id, source_order_id, status,
                   confirm_deadline, confirmed_at, closed_at, close_reason,
                   confirmed_order_id, create_time, update_time
            FROM waitlist_offer
            WHERE id = #{id}
            FOR UPDATE
            """)
    WaitlistOffer lockById(@Param("id") Long id);
}