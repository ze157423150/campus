package com.campus.ticket.controller;

import com.campus.ticket.booking.BookingAcceptedResponse;
import com.campus.ticket.booking.BookingService;
import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.RateLimitResult;
import com.campus.ticket.exception.RateLimitException;
import com.campus.ticket.service.RateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequiredArgsConstructor
@RequestMapping("/activities")
public class RegistrationController
{
    private final BookingService bookingService;
    private final RateLimitService rateLimitService;

    @Value("${campus.rate-limit.booking-user.window-seconds:10}")
    private long bookingWindowSeconds;

    @Value("${campus.rate-limit.booking-user.max-requests:5}")
    private int bookingMaxRequests;

    @PostMapping("/{activityId}/registrations")
    public ResponseEntity<BookingAcceptedResponse> register(@PathVariable("activityId") Long activityId)
    {
        Long userId = UserHolder.getUserId();

        String key = RedisConstants.RATE_LIMIT_SLIDING_KEY_PREFIX + "user:" + userId + ":booking-submit";
        RateLimitResult result = rateLimitService.tryAcquireSlidingWindow(key, Duration.ofSeconds(bookingWindowSeconds), bookingMaxRequests);

        if (!result.allowed())
        {
            throw new RateLimitException(result.retryAfterMillis());
        }

        BookingAcceptedResponse response = bookingService.submit(activityId);
        return ResponseEntity.accepted().body(response);
    }
}