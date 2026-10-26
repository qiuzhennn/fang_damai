package com.example.damai.modules.program.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 演出场次，对应 tr_event_session 表。
 * 同一场演出可以关联多个场次。
 */
@TableName("tr_event_session")
public class EventSession {

    // 场次主键，由数据库自增生成
    @TableId(type = IdType.AUTO)
    private Long id;

    // 所属演出的 ID，对应 tr_event.id
    private Long eventId;

    // 本场演出的开始时间
    private LocalDateTime startTime;

    // 本场演出的结束时间
    private LocalDateTime endTime;

    // 本场次允许开始购票的时间
    private LocalDateTime saleStartTime;

    // 售票状态：0 待开售，1 可售，2 停售
    private Integer status;

    // 记录创建时间
    private LocalDateTime createdAt;

    // 记录最后更新时间
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public LocalDateTime getSaleStartTime() {
        return saleStartTime;
    }

    public void setSaleStartTime(LocalDateTime saleStartTime) {
        this.saleStartTime = saleStartTime;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
}