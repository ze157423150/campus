package com.campus.ticket;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordHashGenerator {

    public static void main(String[] args) {
        // 仅用于本地测试账号
        String hash = new BCryptPasswordEncoder().encode("CampusTest123!");
        System.out.println(hash);
    }
}