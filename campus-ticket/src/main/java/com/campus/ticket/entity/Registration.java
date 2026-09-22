package com.campus.ticket.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Registration {

    private Long id;
    private Long userId;
    private Long activityId;
    private LocalDateTime createTime;
    private String status;
    private LocalDateTime cancelTime;
}