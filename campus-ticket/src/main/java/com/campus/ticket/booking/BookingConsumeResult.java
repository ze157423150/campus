package com.campus.ticket.booking;

public record BookingConsumeResult(
        String status,
        Long registrationId,
        String failureCode)
{
    public static BookingConsumeResult succeeded(Long registrationId)
    {
        return new BookingConsumeResult(
                BookingOrderStatus.SUCCEEDED,
                registrationId,
                null
        );
    }

    public static BookingConsumeResult failed(String failureCode)
    {
        return new BookingConsumeResult(
                BookingOrderStatus.FAILED,
                null,
                failureCode
        );
    }
    public static BookingConsumeResult cancelled(Long registrationId)
    {
        return new BookingConsumeResult(
                BookingOrderStatus.CANCELLED,
                registrationId,
                null
        );
    }
}