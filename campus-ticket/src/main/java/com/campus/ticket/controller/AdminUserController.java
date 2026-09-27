package com.campus.ticket.controller;

import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.RegisterRequest;
import com.campus.ticket.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/users")
public class AdminUserController
{
    private final UserService userService;

    @PostMapping
    public ResponseEntity<Map<String, Long>> create(@RequestBody RegisterRequest request)
    {
        UserHolder.requireAdmin();
        Long userId = userService.register(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("userId", userId));
    }
}
