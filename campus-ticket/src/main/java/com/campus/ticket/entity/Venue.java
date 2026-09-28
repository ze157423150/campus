package com.campus.ticket.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Venue
{
    private Long id;
    private String name;
    private String address;
    private Integer capacity;
    private String description;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
