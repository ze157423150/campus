package com.campus.ticket.controller;

import com.campus.ticket.context.UserHolder;
import com.campus.ticket.service.RegistrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("activities")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/{activityId}/registrations")
    public ResponseEntity<Map<String, Long>> register(
            @PathVariable("activityId") Long activityId
    ) {
        Long userId = UserHolder.getUserId();
        Long registrationId =
                registrationService.register(userId, activityId);

        return ResponseEntity.ok(
                Map.of("registrationId", registrationId)
        );
    }
    @PostMapping("/registrations/{registrationId}/cancel")
    public ResponseEntity<Map<String, String>> cancel(
            @PathVariable("registrationId") Long registrationId
    ) {
        Long userId = UserHolder.getUserId();
        registrationService.cancel(userId, registrationId);

        return ResponseEntity.ok(
                Map.of("message", "报名已取消")
        );
    }
}
