package com.campus.ticket.controller;

import com.campus.ticket.dto.PageResult;
import com.campus.ticket.dto.RegistrationDetail;
import com.campus.ticket.service.RegistrationQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/users")
public class UserRegistrationController {

    private final RegistrationQueryService registrationQueryService;

    public UserRegistrationController(
            RegistrationQueryService registrationQueryService
    ) {
        this.registrationQueryService = registrationQueryService;
    }

    @GetMapping("/me/registrations")
    public PageResult<RegistrationDetail> findMyRegistrations(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(name = "status", required = false) String status
    ) {
        return registrationQueryService.findMine(page, pageSize, status);
    }
}
