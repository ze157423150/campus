package com.campus.ticket.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RegistrationDetail {

    private Long registrationId;
    private Long activityId;
    private String activityTitle;
    private String location;
    private LocalDateTime startTime;
    private LocalDateTime registrationTime;
    private String status;
    private LocalDateTime cancelTime;
}