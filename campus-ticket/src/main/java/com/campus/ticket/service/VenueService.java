package com.campus.ticket.service;

import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.PageResult;
import com.campus.ticket.dto.VenueWriteRequest;
import com.campus.ticket.entity.Venue;
import com.campus.ticket.event.VenueChangedEvent;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.VenueMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class VenueService
{
    private final VenueMapper venueMapper;
    private final VenueCacheService venueCacheService;
    private final ApplicationEventPublisher eventPublisher;

    public Venue findById(Long id)
    {
        validateId(id);
        return requireVenue(venueCacheService.findById(id));
    }

    public Venue findManagementById(Long id)
    {
        UserHolder.requireAdmin();
        validateId(id);
        return requireVenue(venueMapper.findManagementById(id));
    }

    @Transactional(readOnly = true)
    public PageResult<Venue> findPage(int page, int pageSize, String keyword)
    {
        return queryPage(page, pageSize, keyword, "OPEN");
    }

    @Transactional(readOnly = true)
    public PageResult<Venue> findManagementPage(int page, int pageSize, String keyword, String status)
    {
        UserHolder.requireAdmin();
        status = normalize(status);
        if (status != null && !Set.of("OPEN", "CLOSED").contains(status))
        {
            throw invalid("场馆状态只能为 OPEN 或 CLOSED");
        }
        return queryPage(page, pageSize, keyword, status);
    }

    @Transactional
    public Long create(VenueWriteRequest request)
    {
        UserHolder.requireAdmin();
        Venue venue = validateAndBuild(request);
        venueMapper.insert(venue);
        eventPublisher.publishEvent(new VenueChangedEvent(venue.getId()));
        return venue.getId();
    }

    @Transactional
    public void update(Long id, VenueWriteRequest request)
    {
        UserHolder.requireAdmin();
        validateId(id);
        Venue venue = validateAndBuild(request);
        requireVenue(venueMapper.lockById(id));
        venue.setId(id);
        venueMapper.update(venue);
        eventPublisher.publishEvent(new VenueChangedEvent(id));
    }

    private PageResult<Venue> queryPage(int page, int pageSize, String keyword, String status)
    {
        if (page < 1 || pageSize < 1 || pageSize > 100)
        {
            throw invalid("页码必须大于零，每页条数必须在1到100之间");
        }
        keyword = normalize(keyword);
        if (keyword != null && keyword.length() > 100)
        {
            throw invalid("关键词最多100个字符");
        }
        long offset = (page - 1L) * pageSize;
        return new PageResult<>(venueMapper.count(keyword, status), page, pageSize, venueMapper.findPage(keyword, status, offset, pageSize));
    }

    private Venue validateAndBuild(VenueWriteRequest request)
    {
        if (request == null)
        {
            throw invalid("请填写场馆资料");
        }
        String name = normalize(request.name());
        String address = normalize(request.address());
        String description = request.description() == null ? "" : request.description().trim();
        if (name == null || name.length() > 100 || address == null || address.length() > 200)
        {
            throw invalid("场馆名称和地址不能为空，名称最多100字，地址最多200字");
        }
        if (request.capacity() == null || request.capacity() < 1 || request.capacity() > 1000000)
        {
            throw invalid("场馆容量必须在1到1000000之间");
        }
        if (description.length() > 2000)
        {
            throw invalid("场馆简介最多2000字");
        }
        if (request.status() == null || !Set.of("OPEN", "CLOSED").contains(request.status()))
        {
            throw invalid("请明确填写场馆状态 OPEN 或 CLOSED");
        }
        Venue venue = new Venue();
        venue.setName(name);
        venue.setAddress(address);
        venue.setCapacity(request.capacity());
        venue.setDescription(description);
        venue.setStatus(request.status());
        return venue;
    }

    private String normalize(String value)
    {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateId(Long id)
    {
        if (id == null || id <= 0) throw invalid("场馆ID必须为正数");
    }

    private Venue requireVenue(Venue venue)
    {
        if (venue == null) throw new BusinessException(HttpStatus.NOT_FOUND, "VENUE_NOT_FOUND", "场馆不存在或未开放");
        return venue;
    }

    private BusinessException invalid(String message)
    {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }
}
