package com.campus.ticket.service;

import com.campus.ticket.cache.ActivityBloomFilter;
import com.campus.ticket.cache.ActivityCacheStore;
import com.campus.ticket.cache.ActivityRefreshDispatcher;
import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.ActivityCacheData;
import com.campus.ticket.dto.CreateActivityRequest;
import com.campus.ticket.dto.PageResult;
import com.campus.ticket.entity.Activity;
import com.campus.ticket.event.ActivityChangedEvent;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.ActivityMapper;
import com.campus.ticket.mapper.RegistrationMapper;
import lombok.RequiredArgsConstructor;
import org.apache.coyote.OutputBuffer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.campus.ticket.cache.ActivityLocalCache;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityMapper activityMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final RegistrationMapper registrationMapper;
    private final RedissonClient redissonClient;
    private final ActivityRefreshDispatcher activityRefreshDispatcher;
    private final ActivityCacheStore activityCacheStore;
    private final ActivityLocalCache activityLocalCache;
    private final ActivityBloomFilter activityBloomFilter;

    public Activity findById(Long id)
    {
        if (id == null || id <= 0)
        {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "活动ID必须为正数"
            );
        }

        // 先查本地缓存
        String cacheJson = activityLocalCache.get(id);

        if (cacheJson != null)
        {
            return readCachedActivity(id, cacheJson);
        }

        // 本地未命中，再查 Redis，并尝试回填本地
        cacheJson = readRedisAndFillLocal(id);

        if (cacheJson != null)
        {
            return readCachedActivity(id, cacheJson);
        }

        if (!activityBloomFilter.mightContain(id))
        {
            return null;
        }

        // 两级缓存都未命中，沿用原来的互斥重建
        String lockKey = RedisConstants.ACTIVITY_REBUILD_LOCK_PREFIX + id;
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;

        try
        {
            acquired = lock.tryLock(RedisConstants.ACTIVITY_REBUILD_LOCK_WAIT.toMillis(), TimeUnit.MILLISECONDS);

            if (!acquired)
            {
                cacheJson = readRedisAndFillLocal(id);

                if (cacheJson != null)
                {
                    return readCachedActivity(id, cacheJson);
                }

                throw new BusinessException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "ACTIVITY_CACHE_BUSY",
                        "活动查询繁忙，请稍后重试"
                );
            }

            // 拿到锁后再次检查，其他请求可能已经重建完成
            cacheJson = readRedisAndFillLocal(id);

            if (cacheJson != null)
            {
                return readCachedActivity(id, cacheJson);
            }

            String expectedVersion = activityCacheStore.getVersion(id);
            Activity activity = activityMapper.findById(id);

            writeActivityCache(id, activity, expectedVersion);
            return activity;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();

            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "REQUEST_INTERRUPTED",
                    "请求等待被中断，请重试"
            );
        }
        finally
        {
            if (acquired && lock.isHeldByCurrentThread())
            {
                lock.unlock();
            }
        }
    }

    public void writeActivityCache(Long activityId,Activity activity,String expectedVersion){
        if (activity == null) {
            activityCacheStore.writeIfVersionMatches(
                    activityId,
                    expectedVersion,
                    RedisConstants.CACHE_NULL_VALUE,
                    RedisConstants.ACTIVITY_NULL_TTL
            );
            return;
        }

        long jitterSeconds = ThreadLocalRandom.current().nextLong(RedisConstants.ACTIVITY_DETAIL_TTL_JITTER.toSeconds() + 1);
        Duration logicalTtl = RedisConstants.ACTIVITY_DETAIL_TTL.plusSeconds(jitterSeconds);
        Instant expireAt = Instant.now().plus(logicalTtl);

        ActivityCacheData cacheData = new ActivityCacheData(activity, expireAt);
        String cacheJson = jsonMapper.writeValueAsString(cacheData);

        activityCacheStore.writeIfVersionMatches(
                activityId,
                expectedVersion,
                cacheJson,
                RedisConstants.ACTIVITY_DETAIL_PHYSICAL_TTL
        );

    }

    public Activity readCachedActivity(Long activityId,String cacheJson){
        if (RedisConstants.CACHE_NULL_VALUE.equals(cacheJson)) {
            return null;
        }

        ActivityCacheData cacheData = jsonMapper.readValue(cacheJson, ActivityCacheData.class);
        if(!Instant.now().isBefore(cacheData.getExpireAt())){
            activityRefreshDispatcher.submit(activityId,()->{
                refreshActivityCache(activityId);
            });
        }

        return cacheData.getData();
    }

    private void refreshActivityCache(Long activityId){
        String lockKey = RedisConstants.ACTIVITY_REBUILD_LOCK_PREFIX + activityId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;

        try {
            // 后台任务只尝试获取一次，不等待其他刷新任务
            acquired = lock.tryLock();

            if (!acquired) {
                return;
            }

            String cacheKey = RedisConstants.ACTIVITY_DETAIL_KEY_PREFIX + activityId;
            String cacheJson = stringRedisTemplate.opsForValue().get(cacheKey);

            // 排队期间缓存可能被删除，或已变为空值缓存
            if (cacheJson == null || RedisConstants.CACHE_NULL_VALUE.equals(cacheJson)) {
                return;
            }

            ActivityCacheData cacheData = jsonMapper.readValue(cacheJson, ActivityCacheData.class);

            // 其他实例可能已经刷新过，再次检查逻辑过期时间
            if (Instant.now().isBefore(cacheData.getExpireAt())) {
                return;
            }
            String expectedVersion = activityCacheStore.getVersion(activityId);
            Activity activity = activityMapper.findById(activityId);
            writeActivityCache(activityId, activity,expectedVersion);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
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

    @Transactional
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

        activityBloomFilter.add(activity.getId());

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

    private String readRedisAndFillLocal(Long activityId)
    {
        long expectedVersion = activityLocalCache.currentVersion();

        String cacheKey = RedisConstants.ACTIVITY_DETAIL_KEY_PREFIX + activityId;
        String cacheJson = stringRedisTemplate.opsForValue().get(cacheKey);

        if (cacheJson != null)
        {
            activityLocalCache.putIfUnchanged(activityId, cacheJson, expectedVersion);
        }

        return cacheJson;
    }
}
