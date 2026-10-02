package com.campus.ticket.controller;

import com.campus.ticket.booking.BookingAcceptedResponse;
import com.campus.ticket.booking.BookingService;
import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.BookingSubmitRequest;
import com.campus.ticket.dto.BookingSubmitResponse;
import com.campus.ticket.dto.RateLimitResult;
import com.campus.ticket.exception.RateLimitException;
import com.campus.ticket.service.BookingSubmissionService;
import com.campus.ticket.service.RateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequiredArgsConstructor
@RequestMapping("/activities")
public class RegistrationController
{
    private final BookingSubmissionService bookingSubmissionService;    private final RateLimitService rateLimitService;

    @Value("${campus.rate-limit.booking-user.window-seconds:10}")
    private long bookingWindowSeconds;

    @Value("${campus.rate-limit.booking-user.max-requests:5}")
    private int bookingMaxRequests;

    @Value("${campus.rate-limit.booking-api.capacity:100}")
    private int bookingBucketCapacity;

    @Value("${campus.rate-limit.booking-api.refill-per-second:50}")
    private int bookingRefillPerSecond;

    @PostMapping("/{activityId}/registrations")
    public ResponseEntity<BookingSubmitResponse> register(@PathVariable("activityId") Long activityId, @RequestBody(required = false) BookingSubmitRequest request)
    {
        Long userId = UserHolder.getUserId();

        // 第一层：当前用户的报名频率
        String userKey = RedisConstants.RATE_LIMIT_SLIDING_KEY_PREFIX + "user:" + userId + ":booking-submit";
        RateLimitResult userResult = rateLimitService.tryAcquireSlidingWindow(userKey, Duration.ofSeconds(bookingWindowSeconds), bookingMaxRequests);

        if (!userResult.allowed())
        {
            throw new RateLimitException(userResult.retryAfterMillis());
        }

        // 第二层：报名接口整体的放行速度
        String apiKey = RedisConstants.RATE_LIMIT_BUCKET_KEY_PREFIX + "api:booking-submit";
        RateLimitResult apiResult = rateLimitService.tryAcquireTokenBucket(apiKey, bookingBucketCapacity, bookingRefillPerSecond);

        if (!apiResult.allowed())
        {
            throw new RateLimitException(apiResult.retryAfterMillis());
        }

        // 两层检查都通过后，才执行报名
        boolean joinWaitlistIfFull = request != null && request.joinWaitlistIfFull();

        BookingSubmitResponse response = bookingSubmissionService.submit(activityId, joinWaitlistIfFull);

        if ("BOOKING".equals(response.type()))
        {
            return ResponseEntity.accepted().body(response);
        }

        return ResponseEntity.ok(response);
    }
}