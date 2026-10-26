package com.example.damai.modules.order.dto;

import jakarta.validation.constraints.*;

/**
 * 下单请求。
 * 用户身份由后端提供，价格由后端读取票档，不接受客户端指定。
 */
public class OrderCreateRequest {

    @NotNull(message = "票档ID不能为空")
    @Positive(message = "票档ID必须大于0")
    private Long ticketCategoryId;

    @NotNull(message = "购票数量不能为空")
    @Positive(message = "购票数量必须大于0")
    @Max(value = 6, message = "单笔订单最多购买6张")
    private Integer quantity;

    // 一次购买操作的唯一标识；客户端重试时必须复用
    @NotBlank(message = "请求标识不能为空")
    @Pattern(
            regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            message = "请求标识必须为小写UUID格式"
    )
    private String requestId;

    public Long getTicketCategoryId() {
        return ticketCategoryId;
    }

    public void setTicketCategoryId(Long ticketCategoryId) {
        this.ticketCategoryId = ticketCategoryId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    // TODO: 在Redis中原子记录请求参数和预扣结果，避免重试重复扣减。
// TODO: 在订单表增加请求标识及唯一约束，防止数据库重复创建订单。
// TODO: 前端为新购买生成requestId，超时重试复用，不能每次发送都重新生成。
}