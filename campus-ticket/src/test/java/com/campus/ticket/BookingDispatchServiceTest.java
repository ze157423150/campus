package com.campus.ticket;

import com.campus.ticket.booking.BookingDispatchMessage;
import com.campus.ticket.booking.BookingRedisStore;
import com.campus.ticket.service.BookingDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class BookingDispatchServiceTest
{
    private final BookingRedisStore store = mock(BookingRedisStore.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final BookingDispatchService service = new BookingDispatchService(store, kafka);
    private final BookingDispatchMessage message = new BookingDispatchMessage("order-1", "{\"status\":\"PENDING\"}");

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "topic", "test-topic");
        ReflectionTestUtils.setField(service, "immediateDispatchEnabled", true);
    }

    @Test
    void claimedByOtherWorkerDoesNotSend()
    {
        service.dispatchImmediately(1L, "order-1");
        verify(store).claimRequest(1L, "order-1");
        verifyNoInteractions(kafka);
    }

    @Test
    void claimFailureDoesNotRejectAcceptedRequest()
    {
        when(store.claimRequest(1L, "order-1")).thenThrow(new IllegalStateException("Redis unavailable"));
        assertDoesNotThrow(() -> service.dispatchImmediately(1L, "order-1"));
        verifyNoInteractions(kafka);
    }

    @Test
    void synchronousSendFailureLeavesClaimForRecovery()
    {
        when(store.claimRequest(1L, "order-1")).thenReturn(message);
        when(kafka.send("test-topic", message.orderId(), message.requestJson())).thenThrow(new IllegalStateException("Metadata timeout"));
        assertDoesNotThrow(() -> service.dispatchImmediately(1L, "order-1"));
        verify(store).claimRequest(1L, "order-1");
        verify(kafka).send("test-topic", message.orderId(), message.requestJson());
        verifyNoMoreInteractions(store);
    }

    @Test
    void asynchronousSendFailureLeavesClaimForRecovery()
    {
        when(store.claimRequest(1L, "order-1")).thenReturn(message);
        when(kafka.send("test-topic", message.orderId(), message.requestJson())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Delivery failed")));
        assertDoesNotThrow(() -> service.dispatchImmediately(1L, "order-1"));
        verify(store).claimRequest(1L, "order-1");
        verify(kafka).send("test-topic", message.orderId(), message.requestJson());
        verifyNoMoreInteractions(store);
    }

    @Test
    void disabledImmediateDispatchLeavesScheduledPathAvailable()
    {
        ReflectionTestUtils.setField(service, "immediateDispatchEnabled", false);
        service.dispatchImmediately(1L, "order-1");
        verifyNoInteractions(store, kafka);
    }
}
