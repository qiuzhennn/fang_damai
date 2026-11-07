package com.example.damai.modules.order.mq;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * RabbitMQ 异步建单消息。
 *
 * 服务端根据已经校验的购买资料构造，不直接接受客户端提交的价格与用户ID。
 */
public record OrderCreateMessage(

        // 下单用户
        Long userId,

        // 幂等请求标识
        String requestId,

        // 票档ID
        Long ticketCategoryId,

        // 购买数量
        Integer quantity,

        // 服务端确认的单价快照
        BigDecimal unitPrice,

        // 演出开始时间
        LocalDateTime eventStartTime,

        // 服务端受理时间
        LocalDateTime acceptedAt
) {
    public OrderCreateMessage {

        if (eventStartTime != null) {
            eventStartTime = eventStartTime.truncatedTo(ChronoUnit.MILLIS);
        }

        if (acceptedAt != null) {
            acceptedAt = acceptedAt.truncatedTo(ChronoUnit.MILLIS);
        }
    }
    /**
     * 检查购买资料快照是否满足 建单和补偿 共同使用的字段约束。
     * 是否真实占票、是否已有订单、是否允许补偿，由业务入口继续核对。
     */
    public boolean hasValidFields() {
        if (userId == null || userId <= 0 || ticketCategoryId == null || ticketCategoryId <= 0) {
            return false;
        }
        // 同一次购买使用统一格式的请求标识进行识别。
        if (requestId == null || !requestId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
        {
            return false;
        }

        // 单次购买数量限制为1～6张。
        if (quantity == null || quantity < 1 || quantity > 6) {
            return false;
        }

        // 订单按受理时的单价快照计算，金额必须符合订单字段约束。
        if (unitPrice == null || unitPrice.signum() < 0 || unitPrice.scale() > 2 || unitPrice.compareTo(new BigDecimal("9999999999.99")) > 0) {
            return false;
        }

        // 购买必须在演出开始前受理。
        // 后台处理消息时，演出可能已经开始，因此这里核对的是时间快照。
        return acceptedAt != null && eventStartTime != null && acceptedAt.isBefore(eventStartTime);
    }
}