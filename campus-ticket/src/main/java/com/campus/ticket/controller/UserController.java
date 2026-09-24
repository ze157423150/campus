package com.campus.ticket.controller;

import com.campus.ticket.dto.ChangePasswordRequest;
import com.campus.ticket.dto.LoginUser;
import com.campus.ticket.dto.UpdateProfileRequest;
import com.campus.ticket.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/users/me")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping
    public LoginUser profile() {
        return userService.profile();
    }

    @PutMapping
    public LoginUser updateProfile(@RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(request);
    }

    @PutMapping("/password")
    public Map<String, String> changePassword(@RequestBody ChangePasswordRequest request) {
        userService.changePassword(request);
        return Map.of("message", "密码已修改，请重新登录");
    }
}
