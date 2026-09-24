package com.campus.ticket.service;

import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.CreateActivityRequest;
import com.campus.ticket.dto.PageResult;
import com.campus.ticket.entity.Activity;
import com.campus.ticket.event.ActivityChangedEvent;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.ActivityMapper;
import com.campus.ticket.mapper.RegistrationMapper;
import lombok.RequiredArgsConstructor;
import org.apache.coyote.OutputBuffer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityMapper activityMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;
    private static final int CACHE_LOCK_COUNT = 64;
    private final Object[] cacheRebuildLocks = createCacheRebuildLocks();
    private final ApplicationEventPublisher eventPublisher;
    private final RegistrationMapper registrationMapper;


    private Object[] createCacheRebuildLocks() {
        Object[] locks = new Object[CACHE_LOCK_COUNT];

        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private Object getCacheRebuildLock(Long activityId){
        int index = Math.floorMod(Long.hashCode(activityId),CACHE_LOCK_COUNT);
        return cacheRebuildLocks[index];
    }

    public Activity findById(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "活动ID必须为正数"
            );
        }

        String key = RedisConstants.ACTIVITY_DETAIL_KEY_PREFIX + id;
        String cacheJson = stringRedisTemplate.opsForValue().get(key);
        if(cacheJson !=null){
            return readCachedActivity(cacheJson);
        }

        synchronized (getCacheRebuildLock(id)) {
            cacheJson = stringRedisTemplate.opsForValue().get(key);

            if (cacheJson!=null) {
                return readCachedActivity(cacheJson);
            }
            Activity activity = activityMapper.findById(id);
            if (activity == null) {
                stringRedisTemplate.opsForValue().set(key, RedisConstants.CACHE_NULL_VALUE, RedisConstants.ACTIVITY_NULL_TTL);
                return null;
            }

            stringRedisTemplate.opsForValue().set(key, jsonMapper.writeValueAsString(activity), RedisConstants.ACTIVITY_DETAIL_TTL);
            return activity;

        }


    }

    public Activity readCachedActivity(String cacheJson){
        if (RedisConstants.CACHE_NULL_VALUE.equals(cacheJson)) {
            return null;
        }

        return jsonMapper.readValue(cacheJson, Activity.class);
    }

    @Transactional(readOnly = true)
    public PageResult<Activity> findPage(int page, int pageSize, String keyword,
                                         String category, String registrationPhase) {
        keyword = normalizeFilter(keyword);
        category = validateCategory(category);
        registrationPhase = normalizeFilter(registrationPhase);
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "关键词最多100个字符");
        }
        if (registrationPhase != null && !Set.of("NOT_STARTED", "OPEN", "CLOSED").contains(registrationPhase)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                    "报名阶段必须为 NOT_STARTED、OPEN 或 CLOSED");
        }
        LocalDateTime now = LocalDateTime.now();
        long offset = (page - 1L) * pageSize;

        long total = activityMapper.countAll(keyword, category, registrationPhase, now);
        List<Activity> records = activityMapper.findPage(keyword, category, registrationPhase, now, offset, pageSize);

        return new PageResult<>(total, page, pageSize, records);
    }

    private String normalizeFilter(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String validateCategory(String category) {
        category = normalizeFilter(category);
        if (category != null && !Set.of("LECTURE", "SPORTS", "CLUB", "OTHER").contains(category)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                    "活动分类必须为 LECTURE、SPORTS、CLUB 或 OTHER");
        }
        return category;
    }

    public Long create(CreateActivityRequest request){
        UserHolder.requireAdmin();

        validateCreateRequest(request);

        Activity activity = new Activity();
        activity.setTitle(request.getTitle().trim());
        activity.setCategory(request.getCategory() == null ? "OTHER" : request.getCategory());
        activity.setLocation(request.getLocation().trim());
        activity.setStartTime(request.getStartTime());
        activity.setEndTime(request.getEndTime());
        activity.setRegistrationStartTime(request.getRegistrationStartTime());
        activity.setRegistrationEndTime(request.getRegistrationEndTime());
        activity.setTotalQuota(request.getTotalQuota());

        activity.setRemainingQuota(request.getTotalQuota());
        activity.setStatus("DRAFT");

        activityMapper.insert(activity);
        return activity.getId();
    }

    private void validateCreateRequest(CreateActivityRequest request){
        if(request == null
                || request.getTitle() == null
                || request.getTitle().isBlank()
                || request.getLocation() == null
                || request.getLocation().isBlank()
        ){
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "活动标题和地点不能为空"
            );
        }
        if (request.getTitle().trim().length() > 100
                || request.getLocation().trim().length() > 200) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "活动标题最多100个字符，地点最多200个字符"
            );
        }

        request.setCategory(validateCategory(request.getCategory()));

        if (request.getTotalQuota() == null || request.getTotalQuota() <= 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "总名额必须为正数"
            );
        }

        if (request.getStartTime() == null
                || request.getEndTime() == null
                || request.getRegistrationStartTime() == null
                || request.getRegistrationEndTime() == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "活动时间和报名时间必须填写完整"
            );
        }

        LocalDateTime now = LocalDateTime.now();

        if (!request.getStartTime().isAfter(now)
                || !request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ACTIVITY_TIME",
                    "活动开始时间必须晚于当前时间，结束时间必须晚于开始时间"
            );
        }

        if (!request.getRegistrationEndTime()
                .isAfter(request.getRegistrationStartTime())
                || !request.getRegistrationEndTime().isAfter(now)
                || request.getRegistrationEndTime().isAfter(request.getStartTime())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REGISTRATION_TIME",
                    "报名结束时间必须晚于报名开始时间和当前时间，且不能晚于活动开始时间"
            );
        }
    }

    public Activity findMangementById(Long id){
        UserHolder.requireAdmin();

        if(id == null || id<=0){
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "活动ID必须为正数"
            );
        }

        Activity activity = activityMapper.findManagementById(id);

        if(activity == null){
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "ACTIVITY_NOT_FOUND",
                    "活动不存在"
            );
        }
        return activity;
    }

    @Transactional
    public void publish(Long id){
        UserHolder.requireAdmin();

        if (id == null || id <= 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "活动ID必须为正数"
            );
        }

        Activity activity = activityMapper.findByIdForUpdate(id);

        if (activity == null) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "ACTIVITY_NOT_FOUND",
                    "活动不存在"
            );
        }

        if (!"DRAFT".equals(activity.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_ACTIVITY_STATUS",
                    "只有草稿活动可以发布"
            );
        }

        LocalDateTime now = LocalDateTime.now();

        if (!activity.getStartTime().isAfter(now)
                || !activity.getRegistrationEndTime().isAfter(now)) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "ACTIVITY_TIME_EXPIRED",
                    "活动开始时间或报名结束时间已到，请先修改活动时间"
            );
        }

        int publish = activityMapper.publish(activity.getId());

        if(publish !=1 ){
            throw new BusinessException(HttpStatus.CONFLICT,"ACTIVITY_STATE_CONFLICT","活动状态以变化，请重新查询");
        }

        eventPublisher.publishEvent(new ActivityChangedEvent(id));
    }

    @Transactional
    public void updateDraft(Long id,CreateActivityRequest request){
        UserHolder.requireAdmin();

        if(id == null || id<=0){
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "活动ID必须为正数"
            );
        }

        validateCreateRequest(request);
        Activity activity = activityMapper.findByIdForUpdate(id);

        if(activity == null){
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "ACTIVITY_NOT_FOUND",
                    "活动不存在"
            );
        }
        if(!"DRAFT".equals(activity.getStatus())){
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_ACTIVITY_STATUS",
                    "只有草稿活动可以编辑"
            );
        }
        activity.setTitle(request.getTitle().trim());
        activity.setCategory(request.getCategory() == null ? "OTHER" : request.getCategory());
        activity.setLocation(request.getLocation().trim());
        activity.setStartTime(request.getStartTime());
        activity.setEndTime(request.getEndTime());
        activity.setRegistrationStartTime(request.getRegistrationStartTime());
        activity.setRegistrationEndTime(request.getRegistrationEndTime());
        activity.setTotalQuota(request.getTotalQuota());
        activity.setRemainingQuota(request.getTotalQuota());
        int changeRows = activityMapper.updateDraft(activity);
        if ((changeRows != 1)){
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "ACTIVITY_STATE_CONFLICT",
                    "活动更新失败，请重新查询"
            );
        }
    }

    public PageResult<Activity> findManagementPage(
            int page, int pageSize, String status
    ) {
        UserHolder.requireAdmin();

        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "页码必须大于等于1，每页条数必须在1到100之间"
            );
        }

        // 未传状态或只传空白，视为不筛选
        if (status == null || status.isBlank()) {
            status = null;
        } else {
            status = status.trim();

            if (!Set.of("DRAFT", "PUBLISHED", "CANCELLED").contains(status)) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_ARGUMENT",
                        "活动状态必须为 DRAFT、PUBLISHED 或 CANCELLED"
                );
            }
        }

        long offset = (page - 1L) * pageSize;

        long total = activityMapper.countManagement(status);
        List<Activity> records =
                activityMapper.findManagementPage(status, offset, pageSize);

        return new PageResult<>(total, page, pageSize, records);
    }

    @Transactional
    public void cancelActivity(Long activityId) {
        UserHolder.requireAdmin();

        if (activityId == null || activityId <= 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "活动ID必须为正数"
            );
        }

        // 先锁活动，再操作报名，与报名、个人取消保持相同顺序
        Activity activity = activityMapper.findByIdForUpdate(activityId);

        if (activity == null) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "ACTIVITY_NOT_FOUND",
                    "活动不存在"
            );
        }

        if ("CANCELLED".equals(activity.getStatus())) {
            return;
        }

        if (!"DRAFT".equals(activity.getStatus())
                && !"PUBLISHED".equals(activity.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_ACTIVITY_STATUS",
                    "当前活动状态不允许取消"
            );
        }

        if ("PUBLISHED".equals(activity.getStatus())
                && !LocalDateTime.now().isBefore(activity.getStartTime())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "ACTIVITY_ALREADY_STARTED",
                    "活动已开始，不能取消"
            );
        }

        int changedRows = activityMapper.cancelActivity(activityId);

        if (changedRows != 1) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "ACTIVITY_STATE_CONFLICT",
                    "活动状态已变化，请重新查询"
            );
        }

        registrationMapper.cancelAllByActivityId(activityId);

        eventPublisher.publishEvent(
                new ActivityChangedEvent(activityId)
        );
    }
}
