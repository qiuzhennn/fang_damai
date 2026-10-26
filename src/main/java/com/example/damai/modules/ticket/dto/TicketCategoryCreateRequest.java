package com.example.damai.modules.ticket.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 新增票档的请求数据。
 * 可售、锁定和已售库存由后端初始化。
 */
public class TicketCategoryCreateRequest {

    @NotNull(message = "所属场次ID不能为空")
    @Positive(message = "所属场次ID必须大于0")
    private Long sessionId;

    @NotBlank(message = "票档名称不能为空")
    @Size(max = 100, message = "票档名称不能超过100个字符")
    private String name;

    @NotNull(message = "票价不能为空")
    @DecimalMin(value = "0.01", message = "票价不能低于0.01元")
    @Digits(integer = 8, fraction = 2,
            message = "票价最多支持8位整数和2位小数")
    private BigDecimal price;

    @NotNull(message = "总票数不能为空")
    @Positive(message = "总票数必须大于0")
    private Integer totalStock;

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getTotalStock() {
        return totalStock;
    }

    public void setTotalStock(Integer totalStock) {
        this.totalStock = totalStock;
    }
}