package com.campus.ticket.exception;

import org.springframework.http.HttpStatus;

public class RateLimitException extends BusinessException{

    private final long retryAfterMillis;

    public RateLimitException(long retryAfterMillis)
    {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求过于频繁，请稍后重试");
        this.retryAfterMillis = Math.max(1, retryAfterMillis);
    }

    public long getRetryAfterMillis()
    {
        return retryAfterMillis;
    }
}