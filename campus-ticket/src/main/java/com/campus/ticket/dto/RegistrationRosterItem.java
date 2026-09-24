package com.campus.ticket.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class RegistrationRosterItem {
    private Long registrationId;
    private Long userId;
    private String studentNo;
    private String name;
    private String status;
    private LocalDateTime registrationTime;
    private LocalDateTime cancelTime;
}
