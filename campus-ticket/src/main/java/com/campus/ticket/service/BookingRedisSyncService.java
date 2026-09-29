package com.campus.ticket.service;

import com.campus.ticket.booking.BookingMapper;
import com.campus.ticket.booking.BookingOrder;
import com.campus.ticket.booking.BookingOrderStatus;
import com.campus.ticket.booking.BookingRedisStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BookingRedisSyncService
{
    private final BookingMapper bookingMapper;
    private final BookingRedisStore bookingRedisStore;
    private final com.campus.ticket.mapper.WaitlistQuotaMapper waitlistQuotaMapper;
    private final com.campus.ticket.mapper.WaitlistWorkflowMapper waitlistWorkflowMapper;
    private final WaitlistCoordinator waitlistCoordinator;

    // 不包数据库长事务，也不再次取消报名或归还MySQL名额。
    public void synchronize(String orderId)
    {
        BookingOrder order = bookingMapper.find(orderId);

        if (order == null)
        {
            throw new IllegalStateException("待同步订单不存在，orderId=" + orderId);
        }

        boolean cancelled = BookingOrderStatus.CANCELLED.equals(order.getStatus());
        boolean failed = BookingOrderStatus.FAILED.equals(order.getStatus());

        if (!cancelled && !failed)
        {
            throw new IllegalStateException("订单不是可补偿的终态，orderId=" + orderId);
        }

        // 接口、消费者或其他实例可能已经完成同步。
        if (!Boolean.TRUE.equals(order.getRedisDirty()))
        {
            return;
        }

        if (cancelled)
        {
            var quota = waitlistQuotaMapper.findBySourceOrderId(orderId);
            if (quota != null)
            {
                waitlistCoordinator.progress(quota.getId());
                if (waitlistWorkflowMapper.result(quota.getId(), 0L) == null)
                    throw new IllegalStateException("名额接管尚未完成");
                bookingMapper.clean(orderId);
                return;
            }
            bookingRedisStore.markCancelled(order.getActivityId(), order.getUserId(), orderId, order.getEpoch(), order.getRegistrationId());
        }
        else
        {
            bookingRedisStore.markFailed(order.getActivityId(), order.getUserId(), orderId, order.getEpoch(), order.getFailureCode());
        }

        // 只有Lua确认成功后才清除。Redis成功但这一步失败时，下一轮可安全重放Lua。
        bookingMapper.clean(orderId);
    }
}
