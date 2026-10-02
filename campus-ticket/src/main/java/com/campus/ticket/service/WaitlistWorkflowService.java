package com.campus.ticket.service;

import com.campus.ticket.booking.*;
import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.WaitlistTransitionPayload;
import com.campus.ticket.entity.*;
import com.campus.ticket.event.*;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 数据库状态机：固定按活动 -> 名额 -> 邀请/候补加锁。
 * 此类只写数据库与同步任务，不在事务中执行 Redis 网络操作。
 */
@Service
@RequiredArgsConstructor
public class WaitlistWorkflowService
{
    private final ActivityMapper activities;
    private final RegistrationMapper registrations;
    private final BookingMapper orders;
    private final ActivityWaitlistMapper waiting;
    private final WaitlistQuotaMapper quotas;
    private final WaitlistOfferMapper offers;
    private final WaitlistWorkflowMapper db;
    private final WaitlistRedisTaskService tasks;
    private final ApplicationEventPublisher events;

    @Value("${campus.waitlist.confirm-seconds:300}")
    private int confirmSeconds;

    @Transactional
    public boolean advance(Long quotaId)
    {
        WaitlistQuota reference = db.quota(quotaId);
        if (reference == null) return false;
        Activity activity = activities.findByIdForUpdate(reference.getActivityId());
        WaitlistQuota quota = quotas.lockById(quotaId);
        if (activity == null) throw new IllegalStateException("候补活动不存在");
        if (db.pending(quotaId) != 0) return false;
        BookingOrder source = orders.find(quota.getSourceOrderId());
        if (source == null) throw new IllegalStateException("名额来源订单不存在");

        if ("HELD".equals(quota.getStatus()))
        {
            if (!open(activity, db.now()))
            {
                db.closeWaiting(activity.getId());
                returnToPublic(activity, quota, source.getEpoch());
                return true;
            }
            // 每轮工作有上限；大量失效用户交由下一轮继续，避免长期持有活动锁。
            for (int i = 0; i < 20; i++)
            {
                ActivityWaitlist entry = waiting.lockFirstWaiting(activity.getId());
                if (entry == null)
                {
                    returnToPublic(activity, quota, source.getEpoch());
                    return true;
                }
                Registration registration = registrations.findByUserAndActivityForUpdate(entry.getUserId(), activity.getId());
                if (registration != null && !"CANCELLED".equals(registration.getStatus()))
                {
                    db.setWaitlist(entry.getId(), "CANCELLED");
                    continue;
                }
                WaitlistOffer offer = new WaitlistOffer();
                offer.setWaitlistId(entry.getId());
                offer.setQuotaId(quotaId);
                offer.setSourceOrderId(quota.getSourceOrderId());
                // PREPARING 时还不能确认，真正截止时间在 Redis 保留完成后设置。
                offer.setConfirmDeadline(activity.getRegistrationEndTime());
                requireOne(offers.insert(offer));
                requireOne(waiting.markOffered(entry.getId()));
                requireOne(quotas.markOffered(quotaId, offer.getId(), quota.getVersion()));
                quota.setVersion(Math.addExact(quota.getVersion(), 1));
                tasks.createTransition(quota, "OFFER", payload(activity, source, offer, entry, null, null));
                return true;
            }
            return true;
        }

        if (!"OFFERED".equals(quota.getStatus())) return false;
        WaitlistOffer offer = offers.lockById(quota.getCurrentOfferId());
        ActivityWaitlist entry = db.lockWaitlist(offer.getWaitlistId());
        if ("PREPARING".equals(offer.getStatus()))
        {
            String result = db.result(quotaId, quota.getVersion());
            if ("OCCUPIED".equals(result))
            {
                // Lua 已把该版本记为处理完成，但没有占用用户或转出 HELD 名额。
                requireOne(db.closeOffer(offer.getId(), "CANCELLED", "USER_ALREADY_OCCUPIED"));
                db.setWaitlist(entry.getId(), "CANCELLED");
                requireOne(db.move(quotaId, quota.getVersion(), quota.getVersion(), "HELD"));
                return true;
            }
            if (!"APPLIED".equals(result)) throw new IllegalStateException("邀请同步结果不完整");
            LocalDateTime now = db.now();
            if (!open(activity, now))
            {
                release(activity, quota, source, offer, entry, "CANCELLED", "ACTIVITY_CLOSED");
                return true;
            }
            LocalDateTime deadline = now.plusSeconds(Math.max(1, confirmSeconds));
            if (deadline.isAfter(activity.getRegistrationEndTime())) deadline = activity.getRegistrationEndTime();
            if (deadline.isAfter(activity.getStartTime())) deadline = activity.getStartTime();
            requireOne(db.activate(offer.getId(), deadline));
            db.notifyUser(entry.getUserId(), activity.getId(), offer.getId(), "候补已获得名额，请在 " + deadline + " 前确认参加：" + activity.getTitle());
            events.publishEvent(new WaitlistOfferReadyEvent(offer.getId(), deadline));
            return false;
        }
        //超时后执行的分支
        if ("OFFERED".equals(offer.getStatus()))
        {
            LocalDateTime now = db.now();
            if (!open(activity, now) || !now.isBefore(offer.getConfirmDeadline()))
            {
                boolean closed = !open(activity, now);
                release(activity, quota, source, offer, entry, closed ? "CANCELLED" : "EXPIRED", closed ? "ACTIVITY_CLOSED" : "TIMEOUT");
                return true;
            }
        }
        return false;
    }

    @Transactional
    public String confirm(Long offerId)
    {
        Long userId = UserHolder.getUserId();
        WaitlistOffer reference = owned(offerId, userId);
        WaitlistQuota qref = db.quota(reference.getQuotaId());
        Activity activity = activities.findByIdForUpdate(qref.getActivityId());
        WaitlistQuota quota = quotas.lockById(qref.getId());
        WaitlistOffer offer = offers.lockById(offerId);
        ActivityWaitlist entry = db.lockWaitlist(offer.getWaitlistId());
        if ("CONFIRMED".equals(offer.getStatus())) return offer.getConfirmedOrderId();
        LocalDateTime now = db.now();
        if (!"OFFERED".equals(offer.getStatus()) || !offerId.equals(quota.getCurrentOfferId()) || !"OFFERED".equals(quota.getStatus()))
            throw conflict("OFFER_NOT_CONFIRMABLE", "当前邀请不能确认");
        if (!open(activity, now) || !now.isBefore(offer.getConfirmDeadline()))
            throw conflict("OFFER_EXPIRED", "邀请已过期或活动已停止报名");
        if (db.pending(quota.getId()) != 0) throw conflict("OFFER_SYNC_PENDING", "名额正在同步，请稍后重试");
        Registration registration = registrations.findByUserAndActivityForUpdate(userId, activity.getId());
        if (registration != null && !"CANCELLED".equals(registration.getStatus()))
            throw conflict("ALREADY_REGISTERED", "已有有效报名");
        if (registration == null)
        {
            registration = new Registration();
            registration.setActivityId(activity.getId());
            registration.setUserId(userId);
            requireOne(registrations.insert(registration));
        }
        //曾经报名过但是取消了，会走这个分支修改报名状态
        else requireOne(registrations.reactivate(registration.getId()));

        BookingOrder source = orders.find(quota.getSourceOrderId());
        BookingOrder order = new BookingOrder();
        order.setOrderId(UUID.randomUUID().toString());
        order.setRequestKey("waitlist:" + offerId);
        order.setUserId(userId);
        order.setActivityId(activity.getId());
        order.setEpoch(source.getEpoch());
        order.setRegistrationId(registration.getId());
        order.setAcceptedAt(now);
        order.setExpiresAt(offer.getConfirmDeadline());
        requireOne(orders.insertSucceeded(order));
        // 确认领取的是保留名额，因此这里不再次扣 activity.remaining_quota。
        if (db.confirm(offerId, order.getOrderId()) != 1)
            throw conflict("OFFER_EXPIRED", "邀请已过期，确认未生效");
        db.setWaitlist(entry.getId(), "CONFIRMED");
        move(quota, "CONSUMED");
        tasks.createTransition(quota, "CONFIRM", payload(activity, source, offer, entry, order.getOrderId(), registration.getId()));
        orders.log(order.getOrderId(), "WAITLIST_CONFIRMED", "候补确认成功，offerId=" + offerId);
        events.publishEvent(new ActivityChangedEvent(activity.getId()));
        return order.getOrderId();
    }

    @Transactional
    public void decline(Long offerId)
    {
        WaitlistOffer reference = owned(offerId, UserHolder.getUserId());
        WaitlistQuota qref = db.quota(reference.getQuotaId());
        Activity activity = activities.findByIdForUpdate(qref.getActivityId());
        WaitlistQuota quota = quotas.lockById(qref.getId());
        WaitlistOffer offer = offers.lockById(offerId);
        if ("CANCELLED".equals(offer.getStatus()) || "EXPIRED".equals(offer.getStatus())) return;
        if (!"OFFERED".equals(offer.getStatus()) || !offerId.equals(quota.getCurrentOfferId()) || db.pending(quota.getId()) != 0)
            throw conflict("OFFER_NOT_DECLINABLE", "邀请尚未准备好或已确认");
        ActivityWaitlist entry = db.lockWaitlist(offer.getWaitlistId());
        release(activity, quota, orders.find(quota.getSourceOrderId()), offer, entry, "CANCELLED", "USER_DECLINED");
    }

    @Transactional(readOnly = true)
    public WaitlistOffer findMine(Long offerId)
    {
        return owned(offerId, UserHolder.getUserId());
    }

    private WaitlistOffer owned(Long offerId, Long userId)
    {
        if (offerId == null || offerId <= 0) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "邀请ID必须为正数");
        WaitlistOffer offer = db.ownedOffer(offerId, userId);
        if (offer == null) throw new BusinessException(HttpStatus.NOT_FOUND, "OFFER_NOT_FOUND", "邀请不存在");
        return offer;
    }

    private void release(Activity activity, WaitlistQuota quota, BookingOrder source, WaitlistOffer offer, ActivityWaitlist entry, String status, String reason)
    {
        requireOne(db.closeOffer(offer.getId(), status, reason));
        db.setWaitlist(entry.getId(), status);
        move(quota, "HELD");
        tasks.createTransition(quota, "RELEASE", payload(activity, source, offer, entry, null, null));
    }

    private void returnToPublic(Activity activity, WaitlistQuota quota, Long epoch)
    {
        requireOne(quotas.markReturned(quota.getId(), quota.getVersion()));
        // 管理员取消活动时，旧业务已把 remaining_quota 重置为 total_quota。
        if (!"CANCELLED".equals(activity.getStatus())) requireOne(activities.restoreQuota(activity.getId()));
        quota.setStatus("RETURNED");
        quota.setVersion(Math.addExact(quota.getVersion(), 1));
        tasks.createReturnTask(quota, epoch);
        events.publishEvent(new ActivityChangedEvent(activity.getId()));
    }

    private void move(WaitlistQuota quota, String status)
    {
        long version = Math.addExact(quota.getVersion(), 1);
        requireOne(db.move(quota.getId(), quota.getVersion(), version, status));
        quota.setVersion(version);
        quota.setStatus(status);
    }

    private WaitlistTransitionPayload payload(Activity activity, BookingOrder source, WaitlistOffer offer, ActivityWaitlist entry, String orderId, Long registrationId)
    {
        return new WaitlistTransitionPayload(activity.getId(), source.getEpoch(), offer.getId(), entry.getUserId(), orderId, registrationId);
    }

    private boolean open(Activity activity, LocalDateTime now)
    {
        return activity != null && "PUBLISHED".equals(activity.getStatus()) && !now.isBefore(activity.getRegistrationStartTime())
                && now.isBefore(activity.getRegistrationEndTime()) && now.isBefore(activity.getStartTime());
    }

    private void requireOne(int rows)
    {
        if (rows != 1) throw new IllegalStateException("候补状态更新失败，事务已回滚");
    }

    private BusinessException conflict(String code, String message)
    {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    @Transactional
    public void closeWaitingForActivity(Long activityId)
    {
        Activity activity = activities.findByIdForUpdate(activityId);
        if (activity != null && !open(activity, db.now())) db.closeWaiting(activityId);
    }
}
