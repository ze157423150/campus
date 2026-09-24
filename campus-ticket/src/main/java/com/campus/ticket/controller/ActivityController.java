package com.campus.ticket.controller;

import com.campus.ticket.dto.CreateActivityRequest;
import com.campus.ticket.dto.PageResult;
import com.campus.ticket.entity.Activity;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.service.ActivityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "registrationPhase", required = false) String registrationPhase
    ) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "页码必须大于等于1，每页条数必须在1到100之间"
            );
        }

        PageResult<Activity> result = activityService.findPage(page, pageSize, keyword, category, registrationPhase);

        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<Map<String, Long>> create(@RequestBody CreateActivityRequest request) {
        Long activityId = activityService.create(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("activityId", activityId));
    }
    @GetMapping("/{id}/management")
    public Activity findManagementById(@PathVariable("id") Long id) {
        return activityService.findMangementById(id);
    }

    @PostMapping("/{id}/publish")
    public Map<String, String> publish(@PathVariable("id") Long id) {
        activityService.publish(id);
        return Map.of("message", "活动已发布");
    }

    @PutMapping("/{id}")
    public Map<String, String> updateDraft(
            @PathVariable("id") Long id,
            @RequestBody CreateActivityRequest request
    ) {
        activityService.updateDraft(id, request);
        return Map.of("message", "草稿已更新");
    }

    @GetMapping("/management")
    public PageResult<Activity> findManagementPage(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(name = "status", required = false) String status
    ) {
        return activityService.findManagementPage(page, pageSize, status);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, String> cancelActivity(
            @PathVariable("id") Long id
    ) {
        activityService.cancelActivity(id);
        return Map.of("message", "活动已取消");
    }
}