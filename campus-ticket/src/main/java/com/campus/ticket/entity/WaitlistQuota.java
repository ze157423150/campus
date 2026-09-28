package com.campus.ticket.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WaitlistQuota
{
    private Long id;
    private Long activityId;
    private String sourceOrderId;
    private String status;
    private Long currentOfferId;
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}