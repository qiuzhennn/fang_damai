package com.example.damai.modules.program.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

/**
 * 新增演出场次的请求数据。
 */
public class EventSessionCreateRequest {

    // 这里只校验 ID 的格式，演出是否存在需要在 Service 中查询
    @NotNull(message = "所属演出ID不能为空")
    @Positive(message = "所属演出ID必须大于0")
    private Long eventId;

    @NotNull(message = "演出开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "演出结束时间不能为空")
    private LocalDateTime endTime;

    @NotNull(message = "开售时间不能为空")
    private LocalDateTime saleStartTime;

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
}