package com.example.damai.modules.program.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.damai.common.response.PageResponse;
import com.example.damai.modules.program.entity.Event;
import com.example.damai.modules.program.entity.EventSession;
import com.example.damai.modules.program.mapper.EventMapper;
import com.example.damai.modules.program.mapper.EventSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 管理端查询，按业务模块组织；查询条件与原实现一致。 */
@Service
public class AdminProgramQueryService {
    private final EventMapper eventMapper;
    private final EventSessionMapper sessionMapper;

    public AdminProgramQueryService(EventMapper eventMapper, EventSessionMapper sessionMapper) {
        this.eventMapper = eventMapper;
        this.sessionMapper = sessionMapper;
    }

    public PageResponse<Event> events(String city, String keyword,
                                      Integer status, Long categoryId, long page, long size) {
        LambdaQueryWrapper<Event> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(city)) query.eq(Event::getCity, city.trim());
        if (StringUtils.hasText(keyword)) query.like(Event::getTitle, keyword.trim());
        if (status != null) query.eq(Event::getStatus, status);
        if (categoryId != null) query.eq(Event::getCategoryId, categoryId);
        // 不默认限定 status，待上架与已下架也会出现在管理列表。
        query.orderByDesc(Event::getId);
        Page<Event> result = eventMapper.selectPage(new Page<>(page, size), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(),
                result.getCurrent(), result.getSize(), result.getPages());
    }

    public PageResponse<SessionRow> sessions(Long eventId, Integer status,
                                             long page, long size) {
        LambdaQueryWrapper<EventSession> query = new LambdaQueryWrapper<>();
        if (eventId != null) query.eq(EventSession::getEventId, eventId);
        if (status != null) query.eq(EventSession::getStatus, status);
        query.orderByDesc(EventSession::getId);
        Page<EventSession> result = sessionMapper.selectPage(new Page<>(page, size), query);

        // 按本页的演出ID批量查名称，避免逐行查询数据库。
        Map<Long, Event> events = loadEvents(result.getRecords().stream()
                .map(EventSession::getEventId).distinct().toList());
        List<SessionRow> rows = result.getRecords().stream().map(session -> {
            Event event = events.get(session.getEventId());
            return new SessionRow(session.getId(), session.getEventId(),
                    event == null ? null : event.getTitle(),
                    session.getStartTime(), session.getEndTime(),
                    session.getSaleStartTime(), session.getStatus());
        }).toList();
        return new PageResponse<>(rows, result.getTotal(), result.getCurrent(),
                result.getSize(), result.getPages());
    }

    private Map<Long, Event> loadEvents(List<Long> ids) {
        // 空集合不发送 IN 查询，防止意外查询全表。
        if (ids.isEmpty()) return Collections.emptyMap();
        LambdaQueryWrapper<Event> query = new LambdaQueryWrapper<>();
        query.in(Event::getId, ids);
        return eventMapper.selectList(query).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));
    }

    public record SessionRow(Long id, Long eventId, String eventTitle,
                             LocalDateTime startTime, LocalDateTime endTime,
                             LocalDateTime saleStartTime, Integer status) {}
}
