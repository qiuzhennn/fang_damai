package com.example.damai.modules.program.controller.publicapi;

import com.example.damai.common.response.ApiResponse;
import com.example.damai.modules.program.entity.EventCategory;
import com.example.damai.modules.program.service.EventCategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 公开分类列表，只展示启用项。 */
@RestController
@RequestMapping("/public/event-categories")
public class PublicEventCategoryController {
    private final EventCategoryService service;
    public PublicEventCategoryController(EventCategoryService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<EventCategory>> list() {
        return ApiResponse.success(service.list(true));
    }
}
