package com.example.damai.modules.order.mq;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * RabbitMQ 异步建单消息。
 *
 * 只允许服务端根据已经校验的购买资料构造，
 * 不直接接受客户端提交的价格与用户ID。
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
            eventStartTime =
                    eventStartTime.truncatedTo(ChronoUnit.MILLIS);
        }

        if (acceptedAt != null) {
            acceptedAt =
                    acceptedAt.truncatedTo(ChronoUnit.MILLIS);
        }
    }
}