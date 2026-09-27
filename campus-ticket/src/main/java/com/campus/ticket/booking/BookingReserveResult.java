package com.campus.ticket.booking;

public record BookingReserveResult(String code, String orderId)
{
    public boolean success()
    {
        return "OK".equals(code);
    }
}