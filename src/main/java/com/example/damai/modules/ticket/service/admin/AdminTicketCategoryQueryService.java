package com.example.damai.modules.ticket.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.damai.common.response.PageResponse;
import com.example.damai.modules.program.entity.Event;
import com.example.damai.modules.program.entity.EventSession;
import com.example.damai.modules.program.mapper.EventMapper;
import com.example.damai.modules.program.mapper.EventSessionMapper;
import com.example.damai.modules.ticket.entity.TicketCategory;
import com.example.damai.modules.ticket.mapper.TicketCategoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 管理端查询，按业务模块组织；查询条件与原实现一致。 */
@Service
public class AdminTicketCategoryQueryService {
    private final TicketCategoryMapper categoryMapper;
    private final EventSessionMapper sessionMapper;
    private final EventMapper eventMapper;

    public AdminTicketCategoryQueryService(TicketCategoryMapper categoryMapper, EventSessionMapper sessionMapper, EventMapper eventMapper) {
        this.categoryMapper = categoryMapper;
        this.sessionMapper = sessionMapper;
        this.eventMapper = eventMapper;
    }

    public PageResponse<CategoryRow> categories(Long sessionId, String keyword,
                                                long page, long size) {
        LambdaQueryWrapper<TicketCategory> query = new LambdaQueryWrapper<>();
        if (sessionId != null) query.eq(TicketCategory::getSessionId, sessionId);
        if (StringUtils.hasText(keyword)) query.like(TicketCategory::getName, keyword.trim());
        query.orderByDesc(TicketCategory::getId);
        Page<TicketCategory> result = categoryMapper.selectPage(new Page<>(page, size), query);

        List<Long> sessionIds = result.getRecords().stream()
                .map(TicketCategory::getSessionId).distinct().toList();
        Map<Long, EventSession> sessions = Collections.emptyMap();
        if (!sessionIds.isEmpty()) {
            LambdaQueryWrapper<EventSession> sessionQuery = new LambdaQueryWrapper<>();
            sessionQuery.in(EventSession::getId, sessionIds);
            sessions = sessionMapper.selectList(sessionQuery).stream()
                    .collect(Collectors.toMap(EventSession::getId, Function.identity()));
        }
        Map<Long, Event> events = loadEvents(sessions.values().stream()
                .map(EventSession::getEventId).distinct().toList());
        Map<Long, EventSession> sessionMap = sessions;
        List<CategoryRow> rows = result.getRecords().stream().map(category -> {
            EventSession session = sessionMap.get(category.getSessionId());
            Event event = session == null ? null : events.get(session.getEventId());
            return new CategoryRow(category.getId(), category.getSessionId(),
                    session == null ? null : session.getEventId(),
                    event == null ? null : event.getTitle(),
                    session == null ? null : session.getStartTime(),
                    category.getName(), category.getPrice(), category.getTotalStock(),
                    category.getAvailableStock(), category.getLockedStock(), category.getSoldStock());
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

    public record CategoryRow(Long id, Long sessionId, Long eventId,
                              String eventTitle, LocalDateTime startTime,
                              String name, BigDecimal price, Integer totalStock,
                              Integer availableStock, Integer lockedStock,
                              Integer soldStock) {}
}
