package com.campus.ticket.entity;

import lombok.*;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Activity {

    private Long id;
    private String title;
    private String category;
    private String location;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime registrationStartTime;
    private LocalDateTime registrationEndTime;
    private Integer totalQuota;
    private Integer remainingQuota;
    private LocalDateTime createTime;
    private String status;
}
