package com.campus.ticket.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WaitlistOffer
{
    private Long id;
    private Long quotaId;
    private Long waitlistId;
    private String sourceOrderId;
    private String status;
    private LocalDateTime confirmDeadline;
    private LocalDateTime confirmedAt;
    private LocalDateTime closedAt;
    private String closeReason;
    private String confirmedOrderId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}