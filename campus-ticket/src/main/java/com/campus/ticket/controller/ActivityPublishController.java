package com.campus.ticket.controller;

import com.campus.ticket.booking.BookingInventoryService;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.service.ActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/activities")
public class ActivityPublishController
{
    private final ActivityService activityService;
    private final BookingInventoryService bookingInventoryService;

    @PostMapping("/{activityId}/publish-with-inventory")
    public Map<String, String> publishWithInventory(@PathVariable("activityId") Long activityId)
    {
        UserHolder.requireAdmin();

        // 不在外层开启数据库事务：publish正常返回时，发布事务已经提交。
        activityService.publish(activityId);

        try
        {
            bookingInventoryService.initialize(activityId);
        }
        catch (RuntimeException e)
        {
            log.error("活动已发布，但Redis库存初始化未确认，activityId={}", activityId, e);
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ACTIVITY_PUBLISHED_INVENTORY_NOT_READY",
                    "活动已发布，但库存初始化未确认。请检查Redis后使用库存初始化接口核对或重试，无需再次发布",
                    e
            );
        }

        return Map.of("message", "活动已发布，报名库存初始化成功");
    }
}
