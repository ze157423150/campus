package com.campus.ticket.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {
    private String studentNo;
    private String name;
    private String password;
}
