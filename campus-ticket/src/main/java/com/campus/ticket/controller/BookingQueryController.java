package com.campus.ticket.controller;

import com.campus.ticket.booking.BookingQueryResponse;
import com.campus.ticket.booking.BookingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/activities")
public class BookingQueryController
{
    private final BookingQueryService bookingQueryService;

    @GetMapping("/{activityId}/booking-orders/{orderId}")
    public BookingQueryResponse find(@PathVariable("activityId") Long activityId, @PathVariable("orderId") String orderId)
    {
        return bookingQueryService.find(activityId, orderId);
    }
}
