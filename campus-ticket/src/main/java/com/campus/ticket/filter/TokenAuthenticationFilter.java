package com.campus.ticket.filter;

import com.campus.ticket.context.UserHolder;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final AuthService authService;
    private final HandlerExceptionResolver exceptionResolver;

    public TokenAuthenticationFilter(AuthService authService,@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.authService = authService;
        this.exceptionResolver = exceptionResolver;
    }


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        UserHolder.removeUser();
        try {
            // 登录请求无需验证旧 Token，方便过期后重新登录
            boolean loginRequest =
                    "POST".equals(request.getMethod())
                            && ("/auth/login".equals(request.getServletPath())
                            || "/auth/register".equals(request.getServletPath()));

            if (!loginRequest) {
                try {
                    String authorization =
                            request.getHeader("Authorization");

                    if (authorization != null) {
                        if (!authorization.regionMatches(
                                true, 0, "Bearer ", 0, 7)) {
                            throw new BusinessException(
                                    HttpStatus.UNAUTHORIZED,
                                    "UNAUTHORIZED",
                                    "登录凭证格式应为 Bearer Token"
                            );
                        }
                        String token = authorization.substring(7).trim();
                        UserHolder.saveUser(authService.getLoginUser(token));
                    }
                } catch (BusinessException e) {
                    exceptionResolver.resolveException(
                            request, response, null, e
                    );
                    return;
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            UserHolder.removeUser();
        }
    }
}
