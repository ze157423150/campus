package com.campus.ticket.dto;

public record BookingSubmitResponse(String type, String orderId, Long waitlistId, String status)
{
}