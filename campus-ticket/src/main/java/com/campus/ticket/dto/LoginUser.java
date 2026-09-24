package com.campus.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LoginUser {

    private final Long id;
    private final String studentNo;
    private final String name;
    private final String role;
}