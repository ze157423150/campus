package com.campus.ticket.controller;

import com.campus.ticket.dto.PageResult;
import com.campus.ticket.dto.RegistrationRosterItem;
import com.campus.ticket.service.RegistrationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/activities")
@RequiredArgsConstructor
public class ActivityRegistrationController {
    private final RegistrationQueryService registrationQueryService;

    @GetMapping("/{activityId}/registrations")
    public PageResult<RegistrationRosterItem> findRoster(
            @PathVariable("activityId") Long activityId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(name = "status", required = false) String status
    ) {
        return registrationQueryService.findRoster(activityId, page, pageSize, status);
    }
}
