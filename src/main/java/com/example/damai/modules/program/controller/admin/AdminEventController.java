package com.example.damai.modules.program.controller.admin;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.common.response.ApiResponse;
import com.example.damai.common.response.PageResponse;
import com.example.damai.modules.program.dto.EventCreateRequest;
import com.example.damai.modules.program.dto.EventCategoryAssignRequest;
import com.example.damai.modules.program.entity.Event;
import com.example.damai.modules.program.service.EventService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.example.damai.modules.program.service.admin.AdminProgramQueryService;

/** 管理员写操作；业务校验继续由原有 Service 执行。 */
@RestController
@RequestMapping("/admin/events")
public class AdminEventController {
    private final EventService eventService;
    private final AdminProgramQueryService service;

    public AdminEventController(EventService eventService, AdminProgramQueryService service) {
        this.eventService = eventService;
        this.service = service;
    }

    @PostMapping
    public ApiResponse<Boolean> create(@Valid @RequestBody EventCreateRequest request) {

        return ApiResponse.success(eventService.create(request));
    }

    @PutMapping("/{id}/publish")
    public ApiResponse<Boolean> publish(@PathVariable("id") Long id) {
        return ApiResponse.success(eventService.publish(id));
    }

    @PutMapping("/{id}/unpublish")
    public ApiResponse<Boolean> unpublish(
            @PathVariable("id") Long id) {

        return ApiResponse.success(eventService.unpublish(id));
    }
    @GetMapping
    public ApiResponse<PageResponse<Event>> events(
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "categoryId", required = false) Long categoryId,
            @RequestParam(name = "status", required = false) Integer status,
            @RequestParam(name = "page", defaultValue = "1") long page,
            @RequestParam(name = "size", defaultValue = "10") long size) {
        if (categoryId != null && categoryId <= 0) {
            throw new BusinessException("INVALID_PARAM", "分类ID必须大于0");
        }
        validatePage(page, size);
        validateStatus(status);
        return ApiResponse.success(service.events(city, keyword, status, categoryId, page, size));
    }

    private void validatePage(long page, long size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException("INVALID_PAGE_PARAM",
                    "页码必须大于等于1，每页条数必须在1到100之间");
        }
    }

    private void validateStatus(Integer status) {
        if (status != null && (status < 0 || status > 2)) {
            throw new BusinessException("INVALID_PARAM", "状态只能为0、1或2");
        }
    }
    /** 给历史演出补分类，不改变其他业务数据。 */
    @PutMapping("/{id}/category")
    public ApiResponse<Boolean> changeCategory(
            @PathVariable("id") Long id,
            @Valid @RequestBody EventCategoryAssignRequest request) {
        return ApiResponse.success(eventService.changeCategory(id, request.categoryId()));
    }
}
