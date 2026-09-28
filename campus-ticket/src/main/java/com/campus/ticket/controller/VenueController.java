package com.campus.ticket.controller;

import com.campus.ticket.dto.PageResult;
import com.campus.ticket.dto.VenueWriteRequest;
import com.campus.ticket.entity.Venue;
import com.campus.ticket.service.VenueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/venues")
@RequiredArgsConstructor
public class VenueController
{
    private final VenueService venueService;

    @GetMapping("/{id}")
    public Venue findById(@PathVariable("id") Long id)
    {
        return venueService.findById(id);
    }

    @GetMapping
    public PageResult<Venue> findPage(@RequestParam(name = "page", defaultValue = "1") int page, @RequestParam(name = "pageSize", defaultValue = "10") int pageSize, @RequestParam(name = "keyword", required = false) String keyword)
    {
        return venueService.findPage(page, pageSize, keyword);
    }

    @GetMapping("/{id}/management")
    public Venue findManagementById(@PathVariable("id") Long id)
    {
        return venueService.findManagementById(id);
    }

    @GetMapping("/management")
    public PageResult<Venue> findManagementPage(@RequestParam(name = "page", defaultValue = "1") int page, @RequestParam(name = "pageSize", defaultValue = "10") int pageSize, @RequestParam(name = "keyword", required = false) String keyword, @RequestParam(name = "status", required = false) String status)
    {
        return venueService.findManagementPage(page, pageSize, keyword, status);
    }

    @PostMapping
    public ResponseEntity<Map<String, Long>> create(@RequestBody VenueWriteRequest request)
    {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("venueId", venueService.create(request)));
    }

    @PutMapping("/{id}")
    public Map<String, String> update(@PathVariable("id") Long id, @RequestBody VenueWriteRequest request)
    {
        venueService.update(id, request);
        return Map.of("message", "场馆资料已更新");
    }
}
