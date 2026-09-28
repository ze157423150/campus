package com.campus.ticket.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WaitlistRedisTask
{
    private Long id;
    private Long quotaId;
    private Long quotaVersion;
    private String operationType;
    private String payload;
    private String status;
    private Integer attempts;
    private LocalDateTime nextAttemptTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}