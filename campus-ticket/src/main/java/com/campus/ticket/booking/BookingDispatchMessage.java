package com.campus.ticket.booking;

public record BookingDispatchMessage(String orderId, String requestJson)
{
}