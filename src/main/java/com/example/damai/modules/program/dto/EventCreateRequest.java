package com.example.damai.modules.program.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;

/**
 * 新增演出的请求数据。
 * 只接收客户端允许填写的字段。
 */
public class EventCreateRequest {

    // 新增演出必须选择分类；历史数据仍允许未分类
    @NotNull(message = "演出分类不能为空")
    @Positive(message = "分类ID必须大于0")
    private Long categoryId;

    public Long getCategoryId() { return categoryId; }

    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    @NotBlank(message = "演出名称不能为空")
    @Size(max = 200, message = "演出名称不能超过200个字符")
    private String title;

    @NotBlank(message = "艺人或演出方不能为空")
    @Size(max = 100, message = "艺人或演出方不能超过100个字符")
    private String artist;

    @NotBlank(message = "举办城市不能为空")
    @Size(max = 50, message = "举办城市不能超过50个字符")
    private String city;

    @NotBlank(message = "演出场馆不能为空")
    @Size(max = 200, message = "演出场馆不能超过200个字符")
    private String venue;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = venue;
    }
}
