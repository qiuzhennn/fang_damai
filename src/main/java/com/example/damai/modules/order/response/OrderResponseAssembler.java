package com.example.damai.modules.order.response;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.program.entity.Event;
import com.example.damai.modules.program.entity.EventSession;
import com.example.damai.modules.program.mapper.EventMapper;
import com.example.damai.modules.program.mapper.EventSessionMapper;
import com.example.damai.modules.ticket.entity.TicketCategory;
import com.example.damai.modules.ticket.mapper.TicketCategoryMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单展示数据组装器，供用户端和管理端共用。
 * 负责把订单和关联信息组装成 OrderResponse。它不负责权限判断，只处理已经查询出来的订单。
 * 最多增加三次关联查询：票档、场次、演出各查一批，不是每条订单查三次。
 * 空 ID 集合直接跳过查询，避免空条件造成意外的全表查询。
 * 关联数据缺失时仍保留订单，对应展示字段返回 null，不影响读取订单金额和状态。
 */
@Component
public class OrderResponseAssembler {

    private final TicketCategoryMapper categoryMapper;
    private final EventSessionMapper sessionMapper;
    private final EventMapper eventMapper;

    public OrderResponseAssembler(
            TicketCategoryMapper categoryMapper,
            EventSessionMapper sessionMapper,
            EventMapper eventMapper) {
        this.categoryMapper = categoryMapper;
        this.sessionMapper = sessionMapper;
        this.eventMapper = eventMapper;
    }

    /**
     * 单个订单也复用批量组装逻辑。
     * 调用前应已检查订单存在及访问权限。
     */
    public OrderResponse assemble(TicketOrder order) {
        return assembleList(List.of(order)).get(0);
    }

    /**
     * 批量补充展示信息，避免每个订单分别查询关联表。
     */
    public List<OrderResponse> assembleList(List<TicketOrder> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }

        // 1. 批量查询本页订单涉及的票档
        List<Long> categoryIds = orders.stream()
                .map(TicketOrder::getTicketCategoryId)
                .distinct()
                .toList();

        LambdaQueryWrapper<TicketCategory> categoryQuery =
                new LambdaQueryWrapper<>();
        categoryQuery.in(TicketCategory::getId, categoryIds);

        Map<Long, TicketCategory> categories =
                categoryMapper.selectList(categoryQuery).stream()
                        .collect(Collectors.toMap(
                                TicketCategory::getId,
                                Function.identity()
                        ));

        // 2. 根据票档批量查询场次
        List<Long> sessionIds = categories.values().stream()
                .map(TicketCategory::getSessionId)
                .distinct()
                .toList();

        Map<Long, EventSession> sessions;
        if (sessionIds.isEmpty()) {
            sessions = Map.of();
        } else {
            LambdaQueryWrapper<EventSession> sessionQuery =
                    new LambdaQueryWrapper<>();
            sessionQuery.in(EventSession::getId, sessionIds);

            sessions = sessionMapper.selectList(sessionQuery).stream()
                    .collect(Collectors.toMap(
                            EventSession::getId,
                            Function.identity()
                    ));
        }

        // 3. 根据场次批量查询演出
        List<Long> eventIds = sessions.values().stream()
                .map(EventSession::getEventId)
                .distinct()
                .toList();

        Map<Long, Event> events;
        if (eventIds.isEmpty()) {
            events = Map.of();
        } else {
            LambdaQueryWrapper<Event> eventQuery =
                    new LambdaQueryWrapper<>();
            eventQuery.in(Event::getId, eventIds);

            events = eventMapper.selectList(eventQuery).stream()
                    .collect(Collectors.toMap(
                            Event::getId,
                            Function.identity()
                    ));
        }

        // 按原订单列表顺序组装，保持分页排序不变
        return orders.stream()
                .map(order -> {
                    TicketCategory category =
                            categories.get(order.getTicketCategoryId());

                    EventSession session = category == null
                            ? null
                            : sessions.get(category.getSessionId());

                    Event event = session == null
                            ? null
                            : events.get(session.getEventId());

                    return new OrderResponse(
                            order.getId(),
                            order.getUserId(),
                            order.getTicketCategoryId(),

                            session == null ? null : session.getEventId(),
                            event == null ? null : event.getTitle(),
                            event == null ? null : event.getCity(),
                            event == null ? null : event.getVenue(),
                            category == null ? null : category.getSessionId(),
                            session == null ? null : session.getStartTime(),
                            category == null ? null : category.getName(),

                            // 金额必须取订单快照，不取当前票档价格
                            order.getQuantity(),
                            order.getUnitPrice(),
                            order.getTotalAmount(),

                            order.getStatus(),
                            order.getExpireTime(),
                            order.getPaidAt(),
                            order.getCreatedAt(),
                            order.getUpdatedAt()
                    );
                })
                .toList();
    }
}