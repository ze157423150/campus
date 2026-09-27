package com.campus.ticket.booking;

import com.campus.ticket.context.UserHolder;
import com.campus.ticket.exception.BusinessException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class BookingQueryService
{
    private static final Set<String> STATUSES = Set.of("NEW", "PENDING", "SUCCEEDED", "FAILED", "CANCELLED");

    private final BookingMapper bookingMapper;
    private final BookingRedisStore bookingRedisStore;
    private final JsonMapper jsonMapper;

    public BookingQueryResponse find(Long activityId, String orderId)
    {
        Long userId = UserHolder.getUserId();

        if (activityId == null || activityId <= 0 || orderId == null
                || !orderId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "活动ID或订单编号格式不正确");
        }

        try
        {
            // 数据库提交后、Redis回写前，也应返回数据库中的最终结果。
            BookingOrder order = bookingMapper.find(orderId);

            if (order != null)
            {
                requireOwner(userId, activityId, orderId, order.getUserId(), order.getActivityId(), order.getOrderId());
                return response(orderId, order.getStatus(), order.getRegistrationId(), order.getFailureCode());
            }

            String requestJson = bookingRedisStore.findRequestJson(activityId, orderId);

            if (requestJson == null)
            {
                throw notFound();
            }

            CachedRequest request = jsonMapper.readValue(requestJson, CachedRequest.class);

            if (request == null)
            {
                throw new IllegalStateException("Redis申请记录为空");
            }

            requireOwner(userId, activityId, orderId, request.userId(), request.activityId(), request.orderId());
            return response(orderId, request.status(), request.registrationId(), request.failureCode());
        }
        catch (DataAccessException e)
        {
            // 数据库不可用不能被当成“订单不存在”，也不能回退到可能过时的Redis结果。
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_QUERY_UNAVAILABLE", "报名结果暂时无法查询，请稍后重试", e);
        }
    }

    private void requireOwner(Long userId, Long activityId, String orderId, Long actualUserId, Long actualActivityId, String actualOrderId)
    {
        if (!userId.equals(actualUserId) || !activityId.equals(actualActivityId) || !orderId.equals(actualOrderId))
        {
            throw notFound();
        }
    }

    private BookingQueryResponse response(String orderId, String status, Long registrationId, String failureCode)
    {
        if (status == null || !STATUSES.contains(status))
        {
            throw new IllegalStateException("报名订单状态异常");
        }

        // 兼容早期NEW状态；查询接口不修改订单，也不判断或执行超时补偿。
        String visibleStatus = BookingOrderStatus.NEW.equals(status) ? BookingOrderStatus.PENDING : status;
        return new BookingQueryResponse(orderId, visibleStatus, registrationId, failureCode);
    }

    private BusinessException notFound()
    {
        return new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_ORDER_NOT_FOUND", "报名订单不存在");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CachedRequest(String orderId, Long userId, Long activityId, String status, Long registrationId, String failureCode)
    {
    }
}
