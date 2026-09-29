package com.campus.ticket.controller;

import com.campus.ticket.context.UserHolder;
import com.campus.ticket.entity.WaitlistOffer;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.WaitlistWorkflowMapper;
import com.campus.ticket.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class WaitlistOfferController
{
    private final WaitlistWorkflowService workflow;
    private final WaitlistCoordinator coordinator;
    private final WaitlistWorkflowMapper db;

    @GetMapping("/waitlist-offers/{offerId}")
    public WaitlistOffer find(@PathVariable("offerId") Long offerId)
    {
        return workflow.findMine(offerId);
    }

    @PostMapping("/waitlist-offers/{offerId}/confirm")
    public Map<String,String> confirm(@PathVariable("offerId") Long offerId)
    {
        String orderId = workflow.confirm(offerId);
        progress(workflow.findMine(offerId).getQuotaId());
        return Map.of("orderId", orderId, "status", "SUCCEEDED", "message", "候补确认成功");
    }

    @PostMapping("/waitlist-offers/{offerId}/decline")
    public Map<String,String> decline(@PathVariable("offerId") Long offerId)
    {
        workflow.decline(offerId);
        progress(workflow.findMine(offerId).getQuotaId());
        return Map.of("message", "邀请已放弃，名额流转由系统继续处理");
    }

    @GetMapping("/users/me/waitlist-notifications")
    public List<Map<String,Object>> notices(@RequestParam(name="afterId", defaultValue="0") long afterId, @RequestParam(name="limit", defaultValue="20") int limit)
    {
        Long userId = UserHolder.getUserId();
        if (afterId < 0 || limit < 1 || limit > 100) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "分页参数不合法");
        return db.notifications(userId, afterId, limit);
    }

    @PostMapping("/users/me/waitlist-notifications/{id}/read")
    public Map<String,String> read(@PathVariable("id") Long id)
    {
        Long userId = UserHolder.getUserId();
        if (db.ownsNotice(id, userId) != 1) throw new BusinessException(HttpStatus.NOT_FOUND, "NOTICE_NOT_FOUND", "通知不存在");
        db.readNotice(id, userId);
        return Map.of("message", "已读");
    }

    private void progress(Long quotaId)
    {
        try { coordinator.progress(quotaId); }
        catch (RuntimeException e) { log.warn("业务已提交，候补同步转后台，quotaId={}", quotaId, e); }
    }
}

