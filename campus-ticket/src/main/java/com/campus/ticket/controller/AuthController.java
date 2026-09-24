package com.campus.ticket.controller;

import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.LoginRequest;
import com.campus.ticket.dto.LoginUser;
import com.campus.ticket.service.AuthService;
import com.campus.ticket.service.UserService;
import com.campus.ticket.dto.RegisterRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<Map<String, Long>> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("userId", userService.register(request)));
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginRequest request) {
        String token = authService.login(request);
        return Map.of("token", token);
    }

    @PostMapping("/logout")
    public Map<String,String> logout(@RequestHeader(value = "Authorization",required = false) String authorization){
        UserHolder.getUserId();

        String token = authorization.substring(7).trim();
        authService.logout(token);
        return Map.of("message", "已退出登录");
    }
    @GetMapping("/me")
    public LoginUser me() {
        UserHolder.getUserId();
        return UserHolder.getUser();
    }
}
