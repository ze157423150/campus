package com.campus.ticket.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ActivityWaitlist
{
    private Long id;
    private Long activityId;
    private Long userId;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}