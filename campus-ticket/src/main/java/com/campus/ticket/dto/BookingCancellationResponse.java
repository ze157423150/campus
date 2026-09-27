package com.campus.ticket.dto;

public record BookingCancellationResponse(String orderId, String status, String redisSyncStatus, String message)
{
}
