package com.campus.ticket.controller;

import com.campus.ticket.dto.PageResult;
import com.campus.ticket.entity.Activity;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.service.ActivityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/activities")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Activity> findById(@PathVariable("id") Long id) {
        Activity activity = activityService.findById(id);

        if (activity == null) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "ACTIVITY_NOT_FOUND",
                    "活动不存在"
            );
        }

        return ResponseEntity.ok(activity);
    }
    @GetMapping
    public ResponseEntity<PageResult<Activity>> findPage(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize
    ) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "页码必须大于等于1，每页条数必须在1到100之间"
            );
        }

        PageResult<Activity> result = activityService.findPage(page, pageSize);

        return ResponseEntity.ok(result);
    }
}