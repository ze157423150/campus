package com.campus.ticket.controller;

import com.campus.ticket.dto.RegistrationDetail;
import com.campus.ticket.service.RegistrationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/users")
public class UserRegistrationController {

    private final RegistrationService registrationService;

    public UserRegistrationController(
            RegistrationService registrationService
    ) {
        this.registrationService = registrationService;
    }

    @GetMapping("/{userId}/registrations")
    public List<RegistrationDetail> findByUserId(@PathVariable("userId") Long userId) {
        return registrationService.findByUserId(userId);
    }
}