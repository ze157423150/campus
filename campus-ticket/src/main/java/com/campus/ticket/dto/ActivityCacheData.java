package com.campus.ticket.dto;

import com.campus.ticket.entity.Activity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivityCacheData {
    private Activity data;
    private Instant expireAt;
}
