package com.example.damai.modules.order.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AsyncOrderGuardSnapshot(
        Long userId,
        String requestId,
        Long ticketCategoryId,
        Integer quantity,
        String outboxId,
        BigDecimal unitPrice,
        LocalDateTime eventStartTime,
        Integer status,
        Long orderId
) {
}