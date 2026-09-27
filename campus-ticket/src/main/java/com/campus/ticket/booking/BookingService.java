package com.campus.ticket.booking;

import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.service.BookingDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRedisStore bookingRedisStore;
    private final BookingDispatchService bookingDispatchService;

    public BookingAcceptedResponse submit(Long activityId){
        Long userId = UserHolder.getUserId();

        if(activityId == null || activityId <= 0){
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "活动ID必须为正数"
            );
        }

        Long epoch = bookingRedisStore.findEpoch(activityId);

        if(epoch == null){
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "BOOKING_NOT_READY",
                    "活动报名库存尚未准备好"
            );
        }

        String orderId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(RedisConstants.BOOKING_PROCESSING_TIMEOUT);
        BookingReserveResult result;

        try {
            result = bookingRedisStore.reserve(activityId,userId,orderId,epoch,expiresAt);
        }catch (DataAccessException e){
            log.error(
                    "Redis预占结果未确认，activityId={}, userId={}, orderId={}",
                    activityId,
                    userId,
                    orderId,
                    e
            );

            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "BOOKING_RESULT_UNCONFIRMED",
                    "申请结果暂未确认，请稍后查询",
                    e
            );
        }
        if (result.success())
        {
            bookingDispatchService.dispatchImmediately(activityId, result.orderId());
            return new BookingAcceptedResponse(result.orderId(), "PENDING");
        }

        throw toBusinessException(result.code());
    }

    private BusinessException toBusinessException(String code)
    {
        return switch (code)
        {
            case "NOT_STARTED" -> new BusinessException(
                    HttpStatus.CONFLICT,
                    "REGISTRATION_NOT_STARTED",
                    "报名尚未开始"
            );

            case "CLOSED" -> new BusinessException(
                    HttpStatus.CONFLICT,
                    "REGISTRATION_CLOSED",
                    "报名已结束"
            );

            case "SOLD_OUT" -> new BusinessException(
                    HttpStatus.CONFLICT,
                    "QUOTA_EXHAUSTED",
                    "活动名额已满"
            );

            case "DUPLICATE" -> new BusinessException(
                    HttpStatus.CONFLICT,
                    "DUPLICATE_REGISTRATION",
                    "已有报名记录或正在处理的申请，请勿重复提交"
            );

            case "EXPIRED", "TERMINAL" -> new BusinessException(
                    HttpStatus.CONFLICT,
                    "BOOKING_REQUEST_EXPIRED",
                    "该申请已失效"
            );

            case "UNAVAILABLE", "EPOCH_CHANGED" -> new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "BOOKING_NOT_READY",
                    "活动报名暂不可用，请稍后重试"
            );

            default -> new BusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "BOOKING_DATA_ERROR",
                    "报名库存数据异常，请联系管理员"
            );
        };
    }
}
