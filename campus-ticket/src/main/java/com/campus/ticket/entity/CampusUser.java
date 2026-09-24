package com.campus.ticket.entity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CampusUser {

    private Long id;
    private String studentNo;
    private String name;
    private String passwordHash;
    private String role;
}