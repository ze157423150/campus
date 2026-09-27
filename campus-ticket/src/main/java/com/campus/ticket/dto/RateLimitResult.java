package com.campus.ticket.dto;

public record RateLimitResult(boolean allowed, long remaining, long retryAfterMillis)
{
}