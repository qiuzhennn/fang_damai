package com.example.damai.modules.program.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 演出分类；与票档TicketCategory是两种不同业务概念。 */
@TableName("tr_event_category")
public class EventCategory {
    @TableId(type = IdType.AUTO)
    private Long id;
    // 稳定标识，初始化SQL使用code去重，不依赖自增ID
    private String code;
    private String name;
    private Integer sortOrder;
    // 1启用，0停用；停用不修改已有演出或订单
    private Integer status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
