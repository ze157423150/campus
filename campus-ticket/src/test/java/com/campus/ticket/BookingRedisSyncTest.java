package com.campus.ticket;

import com.campus.ticket.booking.BookingMapper;
import com.campus.ticket.booking.BookingOrder;
import com.campus.ticket.booking.BookingRedisStore;
import com.campus.ticket.controller.BookingCancellationController;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.job.BookingRedisSyncJob;
import com.campus.ticket.service.BookingCancellationService;
import com.campus.ticket.service.BookingRedisSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingRedisSyncTest
{
    private final BookingMapper mapper = mock(BookingMapper.class);
    private final BookingRedisStore redis = mock(BookingRedisStore.class);
    private final BookingRedisSyncService service = new BookingRedisSyncService(mapper, redis);

    @Test
    void cancelledOrderSynchronizesRedisBeforeClearingMarker()
    {
        when(mapper.find("A")).thenReturn(order("CANCELLED", true));

        service.synchronize("A");

        var calls = inOrder(redis, mapper);
        calls.verify(redis).markCancelled(10L, 1L, "A", 1L, 100L);
        calls.verify(mapper).clean("A");
        verify(mapper, never()).cancelSucceeded(anyString());
    }

    @Test
    void redisFailureKeepsDirtyMarker()
    {
        when(mapper.find("A")).thenReturn(order("CANCELLED", true));
        doThrow(new IllegalStateException("Redis unavailable")).when(redis).markCancelled(10L, 1L, "A", 1L, 100L);

        assertThrows(IllegalStateException.class, () -> service.synchronize("A"));

        verify(mapper, never()).clean(anyString());
    }

    @Test
    void databaseCleanFailureCanReplayRedisOnNextAttempt()
    {
        when(mapper.find("A")).thenReturn(order("CANCELLED", true));
        doThrow(new IllegalStateException("Database unavailable")).doNothing().when(mapper).clean("A");

        assertThrows(IllegalStateException.class, () -> service.synchronize("A"));
        assertDoesNotThrow(() -> service.synchronize("A"));

        verify(redis, times(2)).markCancelled(10L, 1L, "A", 1L, 100L);
    }

    @Test
    void completedSyncDoesNotTouchRedisAgain()
    {
        when(mapper.find("A")).thenReturn(order("CANCELLED", false));

        service.synchronize("A");

        verifyNoInteractions(redis);
        verify(mapper, never()).clean(anyString());
    }

    @Test
    void expiredFailureUsesFailureCompensation()
    {
        BookingOrder order = order("FAILED", true);
        order.setFailureCode("EXPIRED");
        when(mapper.find("A")).thenReturn(order);

        service.synchronize("A");

        verify(redis).markFailed(10L, 1L, "A", 1L, "EXPIRED");
        verify(mapper).clean("A");
        verify(redis, never()).markCancelled(any(), any(), anyString(), any(), any());
    }

    @Test
    void unexpectedOrderStateDoesNotClearMarker()
    {
        when(mapper.find("A")).thenReturn(order("SUCCEEDED", true));

        assertThrows(IllegalStateException.class, () -> service.synchronize("A"));

        verifyNoInteractions(redis);
        verify(mapper, never()).clean(anyString());
    }

    @Test
    void failedClaimSkipsOrderOwnedByAnotherWorker()
    {
        BookingRedisSyncService worker = mock(BookingRedisSyncService.class);
        when(mapper.findDueRedisSyncOrders(50)).thenReturn(List.of("A"));
        when(mapper.claimRedisSync("A", 30)).thenReturn(0);

        new BookingRedisSyncJob(mapper, worker).synchronizeDueOrders();

        verifyNoInteractions(worker);
    }

    @Test
    void oneFailedOrderDoesNotStopTheRestOfTheBatch()
    {
        BookingRedisSyncService worker = mock(BookingRedisSyncService.class);
        when(mapper.findDueRedisSyncOrders(50)).thenReturn(List.of("A", "B"));
        when(mapper.claimRedisSync(anyString(), eq(30))).thenReturn(1);
        doThrow(new IllegalStateException("Redis unavailable")).when(worker).synchronize("A");

        assertDoesNotThrow(() -> new BookingRedisSyncJob(mapper, worker).synchronizeDueOrders());

        verify(worker).synchronize("B");
        verify(mapper, never()).clean(anyString());
    }

    @Test
    void failedScanLeavesRecoveryForNextScheduledRun()
    {
        BookingRedisSyncService worker = mock(BookingRedisSyncService.class);
        when(mapper.findDueRedisSyncOrders(50)).thenThrow(new IllegalStateException("Database unavailable"));

        assertDoesNotThrow(() -> new BookingRedisSyncJob(mapper, worker).synchronizeDueOrders());

        verify(mapper, never()).claimRedisSync(anyString(), anyInt());
        verifyNoInteractions(worker);
    }

    @Test
    void committedCancellationReturnsAcceptedWhenImmediateRedisSyncFails()
    {
        BookingCancellationService cancellation = mock(BookingCancellationService.class);
        BookingRedisSyncService sync = mock(BookingRedisSyncService.class);
        when(cancellation.cancelInDatabase(10L, "A")).thenReturn(order("CANCELLED", true));
        doThrow(new IllegalStateException("Redis unavailable")).when(sync).synchronize("A");

        var response = new BookingCancellationController(cancellation, sync).cancel(10L, "A");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("CANCELLED", response.getBody().status());
        assertEquals("PENDING", response.getBody().redisSyncStatus());
    }

    @Test
    void completedCancellationReturnsOk()
    {
        BookingCancellationService cancellation = mock(BookingCancellationService.class);
        BookingRedisSyncService sync = mock(BookingRedisSyncService.class);
        when(cancellation.cancelInDatabase(10L, "A")).thenReturn(order("CANCELLED", true));

        var response = new BookingCancellationController(cancellation, sync).cancel(10L, "A");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("COMPLETED", response.getBody().redisSyncStatus());
    }

    @Test
    void rejectedDatabaseCancellationIsNeverReportedAsAccepted()
    {
        BookingCancellationService cancellation = mock(BookingCancellationService.class);
        BookingRedisSyncService sync = mock(BookingRedisSyncService.class);
        BusinessException forbidden = new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_ORDER_NOT_FOUND", "报名订单不存在");
        when(cancellation.cancelInDatabase(10L, "A")).thenThrow(forbidden);

        var controller = new BookingCancellationController(cancellation, sync);

        assertSame(forbidden, assertThrows(BusinessException.class, () -> controller.cancel(10L, "A")));
        verifyNoInteractions(sync);
    }

    private BookingOrder order(String status, boolean dirty)
    {
        BookingOrder order = new BookingOrder();
        order.setOrderId("A");
        order.setActivityId(10L);
        order.setUserId(1L);
        order.setEpoch(1L);
        order.setRegistrationId(100L);
        order.setStatus(status);
        order.setRedisDirty(dirty);
        return order;
    }
}
