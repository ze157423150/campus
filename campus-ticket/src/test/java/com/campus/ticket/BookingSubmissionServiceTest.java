package com.campus.ticket;

import com.campus.ticket.booking.BookingAcceptedResponse;
import com.campus.ticket.booking.BookingService;
import com.campus.ticket.entity.ActivityWaitlist;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.service.BookingSubmissionService;
import com.campus.ticket.service.WaitlistService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingSubmissionServiceTest
{
    private final BookingService booking = mock(BookingService.class);
    private final WaitlistService waiting = mock(WaitlistService.class);
    private final BookingSubmissionService service = new BookingSubmissionService(booking, waiting);

    private BusinessException error(String code)
    {
        return new BusinessException(HttpStatus.CONFLICT, code, code);
    }

    @Test
    void availableSeatDoesNotJoinWaitlist()
    {
        when(booking.submit(1L)).thenReturn(new BookingAcceptedResponse("order", "PENDING"));
        var result = service.submit(1L, true);
        assertEquals("BOOKING", result.type());
        assertEquals("order", result.orderId());
        assertNull(result.waitlistId());
        verify(booking, times(1)).submit(1L);
        verifyNoInteractions(waiting);
    }

    @Test
    void soldOutRequiresOptIn()
    {
        var failure = error("QUOTA_EXHAUSTED");
        when(booking.submit(1L)).thenThrow(failure);
        assertSame(failure, assertThrows(BusinessException.class, () -> service.submit(1L, false)));
        verifyNoInteractions(waiting);
    }

    @Test
    void soldOutReturnsWaitlistIdentityAndActualStatus()
    {
        when(booking.submit(1L)).thenThrow(error("QUOTA_EXHAUSTED"));
        ActivityWaitlist row = new ActivityWaitlist();
        row.setId(12L);
        row.setStatus("OFFERED");
        when(waiting.join(1L)).thenReturn(row);
        var result = service.submit(1L, true);
        assertEquals("WAITLIST", result.type());
        assertEquals(12L, result.waitlistId());
        assertEquals("OFFERED", result.status());
        assertNull(result.orderId());
    }

    @Test
    void uncertainReservationNeverFallsBackToWaitlist()
    {
        var failure = error("BOOKING_RESULT_UNCONFIRMED");
        when(booking.submit(1L)).thenThrow(failure);
        assertSame(failure, assertThrows(BusinessException.class, () -> service.submit(1L, true)));
        verify(booking, times(1)).submit(1L);
        verifyNoInteractions(waiting);
    }

    @Test
    void newlyAvailableSeatRetriesBookingOnce()
    {
        when(booking.submit(1L)).thenThrow(error("QUOTA_EXHAUSTED"))
                .thenReturn(new BookingAcceptedResponse("retry-order", "PENDING"));
        when(waiting.join(1L)).thenThrow(error("QUOTA_AVAILABLE"));
        assertEquals("retry-order", service.submit(1L, true).orderId());
        verify(booking, times(2)).submit(1L);
        verify(waiting, times(1)).join(1L);
    }

    @Test
    void inventoryOscillationHasBoundedRetries()
    {
        when(booking.submit(1L)).thenThrow(error("QUOTA_EXHAUSTED"));
        when(waiting.join(1L)).thenThrow(error("QUOTA_AVAILABLE"));
        var failure = assertThrows(BusinessException.class, () -> service.submit(1L, true));
        assertEquals("BOOKING_STATE_CHANGED", failure.getCode());
        verify(booking, times(2)).submit(1L);
        verify(waiting, times(2)).join(1L);
    }

    @Test
    void waitlistEligibilityFailureIsNotRetried()
    {
        when(booking.submit(1L)).thenThrow(error("QUOTA_EXHAUSTED"));
        var failure = error("WAITLIST_CLOSED");
        when(waiting.join(1L)).thenThrow(failure);
        assertSame(failure, assertThrows(BusinessException.class, () -> service.submit(1L, true)));
        verify(booking, times(1)).submit(1L);
        verify(waiting, times(1)).join(1L);
    }
}
