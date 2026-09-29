package com.campus.ticket.dto;

public record WaitlistTransitionPayload(Long activityId, Long epoch, Long offerId, Long userId, String orderId, Long registrationId)
{
}

