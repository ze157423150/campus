package com.campus.ticket.dto;

public record WaitlistHoldPayload(
        Long activityId,
        String sourceOrderId,
        Long sourceUserId,
        Long registrationId,
        Long epoch
)
{
}