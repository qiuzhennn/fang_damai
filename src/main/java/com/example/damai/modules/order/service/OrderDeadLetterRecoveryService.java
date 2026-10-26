package com.example.damai.modules.order.service;

import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.order.mq.OrderRecoveryDecision;
import com.example.damai.modules.ticket.redis.TicketRedisKeys;
import com.example.damai.modules.ticket.service.TicketStockReservationService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

@Service
public class OrderDeadLetterRecoveryService {

    private final TicketOrderService orderService;
    private final TicketStockReservationService reservationService;
    private final StringRedisTemplate redisTemplate;

    public OrderDeadLetterRecoveryService(
            TicketOrderService orderService,
            TicketStockReservationService reservationService,
            StringRedisTemplate redisTemplate) {

        this.orderService = orderService;
        this.reservationService = reservationService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 对账一条异常建单消息。
     *
     * 这里只自动修复可以明确确认的状态。
     * 不直接补偿库存，不重复创建 MySQL 订单。
     */
    public OrderRecoveryDecision reconcile(
            OrderCreateMessage message,
            String outboxId) {

        Objects.requireNonNull(message, "message");

        if (message.userId() == null
                || message.userId() <= 0
                || message.ticketCategoryId() == null
                || message.ticketCategoryId() <= 0
                || message.quantity() == null
                || message.quantity() <= 0
                || message.requestId() == null
                || !message.requestId().matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        )
                || message.unitPrice() == null
                || message.unitPrice().signum() < 0
                || outboxId == null
                || outboxId.isBlank()) {
            return OrderRecoveryDecision.MANUAL_REVIEW;
        }

        // 1. 读取原始预扣请求
        String requestKey = TicketRedisKeys.request(
                message.userId(),
                message.requestId()
        );

        Map<Object, Object> state =
                redisTemplate.opsForHash().entries(requestKey);

        // 2. 核对消息与原始预扣记录
        if (!"ASYNC".equals(text(state.get("mode")))
                || !outboxId.equals(text(state.get("outboxId")))
                || !message.ticketCategoryId().toString()
                .equals(text(state.get("ticketCategoryId")))
                || !message.quantity().toString()
                .equals(text(state.get("quantity")))) {

            return OrderRecoveryDecision.MANUAL_REVIEW;
        }

        String status = text(state.get("status"));

        // 3. MySQL 是确认订单是否存在的关键依据
        TicketOrder existing = orderService.findByRequestId(
                message.userId(),
                message.requestId()
        );

        // 4. MySQL 暂时查不到订单
        if (existing == null) {

            if ("RESERVED".equals(status)) {
                return OrderRecoveryDecision.RETRY_REQUIRED;
            }

            // 包括 Redis 已创建、已补偿但 MySQL 无订单等情况
            // 暂不直接认定为最终失败
            return OrderRecoveryDecision.MANUAL_REVIEW;
        }

        // 5. 数据库已经存在订单，必须核对购买参数
        if (!Objects.equals(existing.getUserId(), message.userId())
                || !Objects.equals(existing.getRequestId(), message.requestId())
                || !Objects.equals(
                existing.getTicketCategoryId(),
                message.ticketCategoryId()
        )
                || !Objects.equals(
                existing.getQuantity(),
                message.quantity()
        )
                || existing.getUnitPrice() == null
                || existing.getUnitPrice()
                .compareTo(message.unitPrice()) != 0) {

            return OrderRecoveryDecision.MANUAL_REVIEW;
        }

        // 6. 数据库已存在订单，但 Redis 已经补偿
        if ("COMPENSATED".equals(status)) {
            return OrderRecoveryDecision.MANUAL_REVIEW;
        }

        // 7. Redis 已经确认创建成功
        if ("ORDER_CREATED".equals(status)) {

            String recordedOrderId =
                    text(state.get("orderId"));

            if (!existing.getId().toString()
                    .equals(recordedOrderId)) {

                return OrderRecoveryDecision.MANUAL_REVIEW;
            }

            return OrderRecoveryDecision.ALREADY_CREATED;
        }

        // 8. MySQL 创建成功，但 Redis 状态仍然是 RESERVED
        if ("RESERVED".equals(status)) {

            // 1. MySQL订单已经存在，尝试修复Redis
            reservationService.markOrderCreated(
                    message.userId(),
                    message.requestId(),
                    message.ticketCategoryId(),
                    message.quantity(),
                    existing.getId()
            );

            // 2. 重新读取Redis的实际状态
            Map<Object, Object> latestState = redisTemplate.opsForHash().entries(requestKey);

            String latestStatus = text(latestState.get("status"));

            String latestOrderId = text(latestState.get("orderId"));

            // 3. 确认Redis与MySQL最终结果一致
            if ("ORDER_CREATED".equals(latestStatus)
                    && existing.getId().toString()
                    .equals(latestOrderId)
                    && "ASYNC".equals(text(latestState.get("mode")))
                    && outboxId.equals(text(latestState.get("outboxId")))) {

                return OrderRecoveryDecision.REDIS_REPAIRED;
            }

            // 4. 结果不一致，不可以确认恢复完成
            return OrderRecoveryDecision.MANUAL_REVIEW;
        }

        return OrderRecoveryDecision.MANUAL_REVIEW;
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }
}