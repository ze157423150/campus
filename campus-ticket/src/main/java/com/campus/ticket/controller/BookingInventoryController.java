package com.campus.ticket.controller;

import com.campus.ticket.booking.BookingInventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/activities")
public class BookingInventoryController
{
    private final BookingInventoryService bookingInventoryService;

    @PostMapping("/{activityId}/booking-inventory")
    public Map<String, String> initialize(@PathVariable("activityId") Long activityId)
    {
        bookingInventoryService.initialize(activityId);

        return Map.of("message", "报名库存初始化成功");
    }
}