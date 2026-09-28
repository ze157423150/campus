package com.campus.ticket;

import com.campus.ticket.cache.VenueLocalCache;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.LoginUser;
import com.campus.ticket.dto.VenueWriteRequest;
import com.campus.ticket.entity.Venue;
import com.campus.ticket.event.VenueChangedEvent;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.VenueMapper;
import com.campus.ticket.service.VenueCacheService;
import com.campus.ticket.service.VenueService;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VenueServiceTest
{
    private final VenueMapper mapper = mock(VenueMapper.class);
    private final VenueCacheService cache = mock(VenueCacheService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final VenueService service = new VenueService(mapper, cache, publisher);
    private final VenueWriteRequest valid = new VenueWriteRequest("Hall", "Campus", 100, "Description", "OPEN");

    @AfterEach
    void cleanup()
    {
        UserHolder.removeUser();
    }

    @Test
    void anonymousAndStudentCannotManage()
    {
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(BusinessException.class, () -> service.create(valid)).getStatus());
        UserHolder.saveUser(new LoginUser(2L, "student", "Student", "STUDENT"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class, () -> service.update(1L, valid)).getStatus());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class, () -> service.findManagementById(1L)).getStatus());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class, () -> service.findManagementPage(1, 10, null, null)).getStatus());
        verifyNoInteractions(mapper, cache, publisher);
    }

    @Test
    void invalidWritesDoNotReachDatabase()
    {
        admin();
        for (VenueWriteRequest request : new VenueWriteRequest[] {
                new VenueWriteRequest(" ", "Campus", 1, "", "OPEN"),
                new VenueWriteRequest("Hall", "Campus", 0, "", "OPEN"),
                new VenueWriteRequest("Hall", "Campus", 1, "", "UNKNOWN"),
                new VenueWriteRequest("Hall", "Campus", 1, "x".repeat(2001), "OPEN") })
        {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(BusinessException.class, () -> service.create(request)).getStatus());
        }
        verifyNoInteractions(mapper, publisher);
    }

    @Test
    void unchangedUpdateStillInvalidatesAndMissingUpdateDoesNotPublish()
    {
        admin();
        when(mapper.lockById(1L)).thenReturn(new Venue());
        when(mapper.update(any())).thenReturn(0);
        service.update(1L, valid);
        verify(publisher).publishEvent(new VenueChangedEvent(1L));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () -> service.update(2L, valid)).getStatus());
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void publicPagingCannotIncludeClosedVenues()
    {
        service.findPage(1, 10, " Hall ");
        verify(mapper).count("Hall", "OPEN");
        verify(mapper).findPage("Hall", "OPEN", 0L, 10);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(BusinessException.class, () -> service.findPage(0, 10, null)).getStatus());
    }

    @Test
    void invalidationPreventsOldLocalFill()
    {
        VenueLocalCache local = new VenueLocalCache(Caffeine.newBuilder().maximumSize(10).build());
        long beforeRead = local.currentVersion();
        local.invalidate(1L);
        local.putIfUnchanged(1L, "old", beforeRead);
        assertNull(local.get(1L));
        local.putIfUnchanged(1L, "", local.currentVersion());
        assertEquals("", local.get(1L));
    }

    private void admin()
    {
        UserHolder.saveUser(new LoginUser(1L, "admin", "Admin", "ADMIN"));
    }
}
