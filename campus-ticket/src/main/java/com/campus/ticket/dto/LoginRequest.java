package com.campus.ticket.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {

    private String studentNo;
    private String password;
}
