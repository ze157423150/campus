package com.campus.ticket.controller;

import com.campus.ticket.booking.BookingOrder;
import com.campus.ticket.dto.BookingCancellationResponse;
import com.campus.ticket.service.BookingCancellationService;
import com.campus.ticket.service.BookingRedisSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/activities")
public class BookingCancellationController
{
    private final BookingCancellationService bookingCancellationService;
    private final BookingRedisSyncService bookingRedisSyncService;

    @PostMapping("/{activityId}/booking-orders/{orderId}/cancel")
    public ResponseEntity<BookingCancellationResponse> cancel(@PathVariable("activityId") Long activityId, @PathVariable("orderId") String orderId)
    {
        // 独立Service正常返回时，数据库取消事务已经提交
        BookingOrder order = bookingCancellationService.cancelInDatabase(activityId, orderId);

        try
        {
            bookingRedisSyncService.synchronize(order.getOrderId());
        }
        catch (RuntimeException e)
        {
            log.warn(
                    "数据库已取消，Redis同步交由后台继续处理，orderId={}",
                    orderId,
                    e
            );

            return ResponseEntity.accepted().body(new BookingCancellationResponse(
                    orderId, "CANCELLED", "PENDING", "报名已取消，名额同步中，系统将自动重试"
            ));
        }

        return ResponseEntity.ok(new BookingCancellationResponse(
                orderId, "CANCELLED", "COMPLETED", "报名已取消，名额同步完成"
        ));
    }
}
