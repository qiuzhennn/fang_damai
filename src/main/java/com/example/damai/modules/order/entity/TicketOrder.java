package com.example.damai.modules.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 购票订单，对应 tr_order 表。
 * 一个订单购买一个票档，可以购买多张。
 */
@TableName("tr_order")
public class TicketOrder {

    // 订单主键，由数据库自增生成
    @TableId(type = IdType.AUTO)
    private Long id;

    // 下单用户 ID，接入登录后从登录身份获取
    private Long userId;

    // 购买的票档 ID，可通过票档关联到场次和演出
    private Long ticketCategoryId;

    // 购票数量
    private Integer quantity;

    // 下单时的单价快照，不随票档后续调价变化
    private BigDecimal unitPrice;

    // 订单总金额，由后端根据单价和数量计算
    private BigDecimal totalAmount;

    // 订单状态：0 待支付，1 已支付，2 已取消，3 已超时
    private Integer status;

    // 支付截止时间，后续用于判断和处理超时订单
    private LocalDateTime expireTime;

    // 实际支付时间，未支付时为 null
    private LocalDateTime paidAt;

    // 本次购买操作的请求标识，用于数据库幂等校验
    private String requestId;



    // 以下时间由数据库生成
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

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

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }


}