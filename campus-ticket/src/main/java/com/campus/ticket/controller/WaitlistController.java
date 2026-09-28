package com.campus.ticket.controller;

import com.campus.ticket.dto.WaitlistQueryResponse;
import com.campus.ticket.entity.ActivityWaitlist;
import com.campus.ticket.service.WaitlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/activities")
public class WaitlistController
{
    private final WaitlistService waitlistService;

    @PostMapping("/{activityId}/waitlist")
    public ActivityWaitlist join(@PathVariable("activityId") Long activityId)
    {
        return waitlistService.join(activityId);
    }

    @GetMapping("/{activityId}/waitlist/me")
    public WaitlistQueryResponse findCurrent(@PathVariable("activityId") Long activityId)
    {
        return waitlistService.findCurrent(activityId);
    }

    @PostMapping("/{activityId}/waitlist/{waitlistId}/cancel")
    public Map<String, String> cancelWaiting(@PathVariable("activityId") Long activityId, @PathVariable("waitlistId") Long waitlistId)
    {
        waitlistService.cancelWaiting(activityId, waitlistId);
        return Map.of("message", "已退出候补");
    }
}