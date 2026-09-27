package com.campus.ticket;

import com.campus.ticket.booking.BookingInventoryService;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.controller.ActivityPublishController;
import com.campus.ticket.controller.AdminUserController;
import com.campus.ticket.dto.LoginUser;
import com.campus.ticket.dto.RegisterRequest;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.service.ActivityService;
import com.campus.ticket.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminWorkflowTest
{
    private final ActivityService activityService = mock(ActivityService.class);
    private final BookingInventoryService inventoryService = mock(BookingInventoryService.class);
    private final UserService userService = mock(UserService.class);

    @AfterEach
    void clearUser()
    {
        UserHolder.removeUser();
    }

    @Test
    void rejectedPublicationDoesNotInitializeInventory()
    {
        loginAdmin();
        BusinessException conflict = new BusinessException(HttpStatus.CONFLICT, "INVALID_ACTIVITY_STATUS", "只有草稿可以发布");
        doThrow(conflict).when(activityService).publish(10L);
        ActivityPublishController controller = new ActivityPublishController(activityService, inventoryService);

        assertSame(conflict, assertThrows(BusinessException.class, () -> controller.publishWithInventory(10L)));
        verifyNoInteractions(inventoryService);
    }

    @Test
    void inventoryFailureReportsPartialCompletion()
    {
        loginAdmin();
        doThrow(new IllegalStateException("Redis unavailable")).when(inventoryService).initialize(10L);
        ActivityPublishController controller = new ActivityPublishController(activityService, inventoryService);

        BusinessException exception = assertThrows(BusinessException.class, () -> controller.publishWithInventory(10L));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("ACTIVITY_PUBLISHED_INVENTORY_NOT_READY", exception.getCode());
        var calls = inOrder(activityService, inventoryService);
        calls.verify(activityService).publish(10L);
        calls.verify(inventoryService).initialize(10L);
    }

    @Test
    void studentCannotUseAdminEndpoints()
    {
        UserHolder.saveUser(new LoginUser(2L, "student", "Student", "STUDENT"));
        ActivityPublishController publication = new ActivityPublishController(activityService, inventoryService);
        AdminUserController users = new AdminUserController(userService);

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class, () -> publication.publishWithInventory(10L)).getStatus());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class, () -> users.create(new RegisterRequest())).getStatus());
        verifyNoInteractions(activityService, inventoryService, userService);
    }

    @Test
    void anonymousUserCannotCreateAccountsThroughAdminEndpoint()
    {
        AdminUserController controller = new AdminUserController(userService);

        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(BusinessException.class, () -> controller.create(new RegisterRequest())).getStatus());
        verifyNoInteractions(userService);
    }

    @Test
    void administratorCreatesStudentUsingExistingRegistrationService()
    {
        loginAdmin();
        RegisterRequest request = new RegisterRequest();
        request.setStudentNo("20260001");
        request.setName("Test Student");
        request.setPassword("Test123456");
        when(userService.register(request)).thenReturn(20L);

        var response = new AdminUserController(userService).create(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(20L, response.getBody().get("userId"));
    }

    private void loginAdmin()
    {
        UserHolder.saveUser(new LoginUser(1L, "admin", "Admin", "ADMIN"));
    }
}
