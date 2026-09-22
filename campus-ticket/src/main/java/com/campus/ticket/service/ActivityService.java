package com.campus.ticket.service;

import com.campus.ticket.dto.PageResult;
import com.campus.ticket.entity.Activity;
import com.campus.ticket.mapper.ActivityMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ActivityService {

    private final ActivityMapper activityMapper;

    public ActivityService(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    public Activity findById(Long id) {
        return activityMapper.findById(id);
    }

    public PageResult<Activity> findPage(int page, int pageSize) {
        long offset = (page - 1L) * pageSize;

        long total = activityMapper.countAll();
        List<Activity> records = activityMapper.findPage(offset, pageSize);

        return new PageResult<>(total, page, pageSize, records);
    }
}