package com.example.damai.modules.program.controller.admin;

import com.example.damai.common.response.ApiResponse;
import com.example.damai.modules.program.entity.EventCategory;
import com.example.damai.modules.program.service.EventCategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理员读取全部分类，包含停用项，便于识别历史分类。 */
@RestController
@RequestMapping("/admin/event-categories")
public class AdminEventCategoryController {
    private final EventCategoryService service;
    public AdminEventCategoryController(EventCategoryService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<EventCategory>> list() {
        return ApiResponse.success(service.list(false));
    }
}
