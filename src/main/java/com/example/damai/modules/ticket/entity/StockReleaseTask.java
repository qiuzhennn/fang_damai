package com.example.damai.modules.ticket.entity;

/**
 * 执行Redis库存释放所需的MySQL任务快照。
 */
public record StockReleaseTask(
        Long id,
        Long orderId,
        Long userId,
        String requestId,
        Long ticketCategoryId,
        Integer quantity
) {
}