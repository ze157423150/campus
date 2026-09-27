package com.campus.ticket.exception;

import com.campus.ticket.dto.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(
                new ErrorResponse("INVALID_ARGUMENT", "请求正文缺失或JSON字段格式不正确"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException exception
    ) {
        ErrorResponse body = new ErrorResponse(
                exception.getCode(),
                exception.getMessage()
        );

        return ResponseEntity
                .status(exception.getStatus())
                .body(body);
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception
    ) {
        ErrorResponse body = new ErrorResponse(
                "INVALID_ARGUMENT",
                "参数 " + exception.getName() + " 类型或格式不正确"
        );

        return ResponseEntity.badRequest().body(body);
    }
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException exception
    ) {
        ErrorResponse body = new ErrorResponse(
                "MISSING_PARAMETER",
                "缺少必填参数 " + exception.getParameterName()
        );

        return ResponseEntity.badRequest().body(body);
    }
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitException(RateLimitException exception)
    {
        long retryAfterMillis = exception.getRetryAfterMillis();
        long retryAfterSeconds = retryAfterMillis / 1000;

        if (retryAfterMillis % 1000 != 0)
        {
            retryAfterSeconds++;
        }

        ErrorResponse body = new ErrorResponse(exception.getCode(), exception.getMessage());

        return ResponseEntity.status(exception.getStatus())
                .header("Retry-After", Long.toString(retryAfterSeconds))
                .body(body);
    }
}
