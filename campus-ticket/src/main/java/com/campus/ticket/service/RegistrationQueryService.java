package com.campus.ticket.service;

import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.PageResult;
import com.campus.ticket.dto.RegistrationDetail;
import com.campus.ticket.dto.RegistrationRosterItem;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.ActivityMapper;
import com.campus.ticket.mapper.RegistrationQueryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegistrationQueryService {
    private final RegistrationQueryMapper queryMapper;
    private final ActivityMapper activityMapper;

    @Transactional(readOnly = true)
    public PageResult<RegistrationDetail> findMine(int page, int pageSize, String status) {
        Long userId = UserHolder.getUserId();
        validatePage(page, pageSize);
        String filter = normalizeStatus(status);
        long total = queryMapper.countMine(userId, filter);
        return new PageResult<>(total, page, pageSize,
                queryMapper.findMine(userId, filter, (page - 1L) * pageSize, pageSize));
    }

    @Transactional(readOnly = true)
    public PageResult<RegistrationRosterItem> findRoster(Long activityId, int page, int pageSize, String status) {
        UserHolder.requireAdmin();
        if (activityId == null || activityId <= 0) throw invalid("活动ID必须为正数");
        validatePage(page, pageSize);
        String filter = normalizeStatus(status);
        if (activityMapper.findManagementById(activityId) == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "活动不存在");
        }
        long total = queryMapper.countRoster(activityId, filter);
        return new PageResult<>(total, page, pageSize,
                queryMapper.findRoster(activityId, filter, (page - 1L) * pageSize, pageSize));
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw invalid("页码必须大于等于1，每页条数必须在1到100之间");
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String value = status.trim();
        if (!"REGISTERED".equals(value) && !"CANCELLED".equals(value)) {
            throw invalid("报名状态必须为 REGISTERED 或 CANCELLED");
        }
        return value;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }
}
