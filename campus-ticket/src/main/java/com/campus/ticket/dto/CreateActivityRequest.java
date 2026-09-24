package com.campus.ticket.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateActivityRequest {

    private String title;
    private String category;
    private String location;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime registrationStartTime;
    private LocalDateTime registrationEndTime;
    private Integer totalQuota;
}