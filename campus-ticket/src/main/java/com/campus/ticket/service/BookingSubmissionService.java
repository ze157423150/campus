package com.campus.ticket.service;

import com.campus.ticket.booking.BookingAcceptedResponse;
import com.campus.ticket.booking.BookingService;
import com.campus.ticket.dto.BookingSubmitResponse;
import com.campus.ticket.entity.ActivityWaitlist;
import com.campus.ticket.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BookingSubmissionService
{
    private final BookingService bookingService;
    private final WaitlistService waitlistService;

    public BookingSubmitResponse submit(Long activityId, boolean joinWaitlistIfFull)
    {
        // 首次尝试后，如果加入候补时发现名额刚好恢复，最多再尝试一次。
        for (int attempt = 0; attempt < 2; attempt++)
        {
            try
            {
                BookingAcceptedResponse booking = bookingService.submit(activityId);

                return new BookingSubmitResponse(
                        "BOOKING",
                        booking.orderId(),
                        null,
                        booking.status()
                );
            }
            catch (BusinessException e)
            {
                if (!joinWaitlistIfFull || !"QUOTA_EXHAUSTED".equals(e.getCode()))
                {
                    throw e;
                }
            }

            // 只有明确满额，且用户同意自动候补，才会执行到这里。
            try
            {
                ActivityWaitlist waitlist = waitlistService.join(activityId);

                return new BookingSubmitResponse(
                        "WAITLIST",
                        null,
                        waitlist.getId(),
                        waitlist.getStatus()
                );
            }
            catch (BusinessException e)
            {
                if (!"QUOTA_AVAILABLE".equals(e.getCode()))
                {
                    throw e;
                }
            }
        }

        throw new BusinessException(
                HttpStatus.CONFLICT,
                "BOOKING_STATE_CHANGED",
                "名额状态正在变化，请稍后重新提交"
        );
    }
}