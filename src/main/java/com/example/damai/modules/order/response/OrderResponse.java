package com.example.damai.modules.order.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单展示响应。
 * 订单字段来自 tr_order，演出相关信息通过关联查询补充。
 */
public record OrderResponse(
        Long id,
        Long userId,
        Long ticketCategoryId,

        // 关联的演出、场次和票档信息
        Long eventId,
        String eventTitle,
        String city,
        String venue,
        Long sessionId,
        LocalDateTime startTime,
        String ticketCategoryName,

        // 下单时保存的数量与金额快照
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount,

        // 订单状态与时间
        Integer status,
        LocalDateTime expireTime,
        LocalDateTime paidAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}