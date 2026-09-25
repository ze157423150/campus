package com.campus.ticket.booking;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BookingOrder
{
    private String orderId;
    private Long userId;
    private Long activityId;
    private String requestKey;
    private Long epoch;
    private String status;
    private Long registrationId;
    private LocalDateTime acceptedAt;
    private LocalDateTime expiresAt;
    private String failureCode;
    private Integer consumeFailures;
    private Boolean redisDirty;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
