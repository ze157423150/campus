package com.campus.ticket.dto;

import com.campus.ticket.entity.Venue;
import java.time.Instant;

public record VenueCacheData(Venue data, Instant expireAt)
{
}
