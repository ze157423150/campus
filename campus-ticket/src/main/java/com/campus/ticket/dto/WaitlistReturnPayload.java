package com.campus.ticket.dto;

public record WaitlistReturnPayload(
        Long activityId,
        String sourceOrderId,
        Long epoch
)
{
}