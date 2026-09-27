package com.campus.ticket.booking;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record BookingQueryResponse(String orderId, String status, Long registrationId, String failureCode)
{
}
