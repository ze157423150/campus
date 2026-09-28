package com.campus.ticket.dto;

import java.time.LocalDateTime;

public record WaitlistQueryResponse(
        Long waitlistId,
        Long activityId,
        String status,
        Long waitingAhead,
        LocalDateTime createTime
)
{
}