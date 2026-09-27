package com.campus.ticket;

import com.campus.ticket.booking.*;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.LoginUser;
import com.campus.ticket.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingQueryServiceTest
{
    private static final String ORDER_ID = "7fed70df-5c03-472f-944d-b5b8a8380433";
    private final BookingMapper mapper = mock(BookingMapper.class);
    private final BookingRedisStore redis = mock(BookingRedisStore.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final BookingQueryService service = new BookingQueryService(mapper, redis, jsonMapper);

    @BeforeEach
    void login()
    {
        UserHolder.saveUser(new LoginUser(1L, "student", "Student", "STUDENT"));
    }

    @AfterEach
    void clearUser()
    {
        UserHolder.removeUser();
    }

    @Test
    void databaseSuccessWinsWithoutReadingRedis()
    {
        BookingOrder order = order("SUCCEEDED");
        order.setRegistrationId(12L);
        when(mapper.find(ORDER_ID)).thenReturn(order);

        assertEquals(new BookingQueryResponse(ORDER_ID, "SUCCEEDED", 12L, null), service.find(10L, ORDER_ID));
        verifyNoInteractions(redis);
    }

    @Test
    void failureIsVisibleWhileCompensationIsStillPending()
    {
        BookingOrder order = order("FAILED");
        order.setFailureCode("EXPIRED");
        order.setRedisDirty(true);
        when(mapper.find(ORDER_ID)).thenReturn(order);

        assertEquals("EXPIRED", service.find(10L, ORDER_ID).failureCode());
        verifyNoInteractions(redis);
        verify(mapper, never()).clean(anyString());
    }

    @Test
    void redisPendingAcceptsStringIdsAndDoesNotExpireTheOrder()
    {
        when(redis.findRequestJson(10L, ORDER_ID)).thenReturn(cached("1", "PENDING", ""));

        assertEquals(new BookingQueryResponse(ORDER_ID, "PENDING", null, null), service.find(10L, ORDER_ID));
        verify(redis, never()).markFailed(any(), anyString());
    }

    @Test
    void redisTerminalResultIncludesRegistrationId()
    {
        when(redis.findRequestJson(10L, ORDER_ID)).thenReturn(cached("1", "SUCCEEDED", ",\"registrationId\":\"12\""));

        assertEquals(12L, service.find(10L, ORDER_ID).registrationId());
    }

    @Test
    void otherUsersDatabaseOrderIsNotExposedOrLookedUpInRedis()
    {
        BookingOrder order = order("SUCCEEDED");
        order.setUserId(2L);
        when(mapper.find(ORDER_ID)).thenReturn(order);

        assertStatus(HttpStatus.NOT_FOUND, 10L, ORDER_ID);
        verifyNoInteractions(redis);
    }

    @Test
    void otherUsersRedisRequestIsNotExposed()
    {
        when(redis.findRequestJson(10L, ORDER_ID)).thenReturn(cached("2", "PENDING", ""));
        assertStatus(HttpStatus.NOT_FOUND, 10L, ORDER_ID);
    }

    @Test
    void incorrectActivityCannotAccessOrder()
    {
        when(mapper.find(ORDER_ID)).thenReturn(order("SUCCEEDED"));
        assertStatus(HttpStatus.NOT_FOUND, 11L, ORDER_ID);
        verifyNoInteractions(redis);
    }

    @Test
    void missingOrderReturnsNotFound()
    {
        assertStatus(HttpStatus.NOT_FOUND, 10L, ORDER_ID);
    }

    @Test
    void databaseFailureDoesNotFallBackToRedis()
    {
        when(mapper.find(ORDER_ID)).thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, 10L, ORDER_ID);
        verifyNoInteractions(redis);
    }

    @Test
    void anonymousAndInvalidRequestsDoNotReachStorage()
    {
        UserHolder.removeUser();
        assertStatus(HttpStatus.UNAUTHORIZED, 10L, ORDER_ID);
        login();
        assertStatus(HttpStatus.BAD_REQUEST, 0L, ORDER_ID);
        assertStatus(HttpStatus.BAD_REQUEST, 10L, "invalid");
        verifyNoInteractions(mapper, redis);
    }

    private BookingOrder order(String status)
    {
        BookingOrder order = new BookingOrder();
        order.setOrderId(ORDER_ID);
        order.setActivityId(10L);
        order.setUserId(1L);
        order.setStatus(status);
        return order;
    }

    private String cached(String userId, String status, String extra)
    {
        return "{\"orderId\":\"" + ORDER_ID + "\",\"userId\":\"" + userId
                + "\",\"activityId\":\"10\",\"status\":\"" + status
                + "\",\"epoch\":\"1\",\"acceptedAtMillis\":1,\"expiresAtMillis\":2" + extra + "}";
    }

    private void assertStatus(HttpStatus expected, Long activityId, String orderId)
    {
        assertEquals(expected, assertThrows(BusinessException.class, () -> service.find(activityId, orderId)).getStatus());
    }
}
