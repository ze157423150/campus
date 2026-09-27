package com.campus.ticket.booking;

public record BookingMessage(
        String orderId,
        Long userId,
        Long activityId,
        Long epoch,
        String status,
        Long acceptedAtMillis,
        Long expiresAtMillis)
{
}