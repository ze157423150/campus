package com.campus.ticket.filter;

import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.dto.RateLimitResult;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.exception.RateLimitException;
import com.campus.ticket.service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;

@Component
@Order(10)
public class IpRateLimitFilter extends OncePerRequestFilter
{
    private static final Pattern BOOKING_PATH = Pattern.compile("^/activities/[^/]+/registrations$");

    private final RateLimitService rateLimitService;
    private final HandlerExceptionResolver exceptionResolver;

    @Value("${campus.rate-limit.login-ip.window-seconds:60}")
    private long loginWindowSeconds;

    @Value("${campus.rate-limit.login-ip.max-requests:20}")
    private int loginMaxRequests;

    @Value("${campus.rate-limit.booking-ip.window-seconds:10}")
    private long bookingWindowSeconds;

    @Value("${campus.rate-limit.booking-ip.max-requests:300}")
    private int bookingMaxRequests;

    public IpRateLimitFilter(RateLimitService rateLimitService, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
    {
        this.rateLimitService = rateLimitService;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException
    {
        if (!"POST".equals(request.getMethod()))
        {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getServletPath();
        boolean loginRequest = "/auth/login".equals(path);
        boolean bookingRequest = BOOKING_PATH.matcher(path).matches();

        if (!loginRequest && !bookingRequest)
        {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = request.getRemoteAddr();

        try
        {
            if (loginRequest)
            {
                checkLimit(ip, "auth-login", loginWindowSeconds, loginMaxRequests);
            }
            else
            {
                checkLimit(ip, "booking-submit", bookingWindowSeconds, bookingMaxRequests);
            }
        }
        catch (BusinessException e)
        {
            if (exceptionResolver.resolveException(request, response, null, e) == null)
            {
                throw e;
            }

            return;
        }

        filterChain.doFilter(request, response);
    }

    private void checkLimit(String ip, String resource, long windowSeconds, int maxRequests)
    {
        String key = RedisConstants.RATE_LIMIT_SLIDING_KEY_PREFIX + "ip:" + ip + ":" + resource;
        RateLimitResult result = rateLimitService.tryAcquireSlidingWindow(key, Duration.ofSeconds(windowSeconds), maxRequests);

        if (!result.allowed())
        {
            throw new RateLimitException(result.retryAfterMillis());
        }
    }
}