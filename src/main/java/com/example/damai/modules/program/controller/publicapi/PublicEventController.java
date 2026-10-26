package com.example.damai.modules.program.controller.publicapi;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.damai.common.exception.BusinessException;
import com.example.damai.common.response.ApiResponse;
import com.example.damai.common.response.PageResponse;
import com.example.damai.modules.program.entity.Event;
import com.example.damai.modules.program.service.EventService;
import org.springframework.web.bind.annotation.*;

/** 公开浏览接口；仅提供原有查询，不包含管理写操作。 */
@RestController
@RequestMapping("/public/events")
public class PublicEventController {
    private final EventService eventService;

    public PublicEventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/{id}")
    public ApiResponse<Event> getById(@PathVariable("id") Long id) {
        Event event = eventService.getById(id);
        return ApiResponse.success(event);
    }

    @GetMapping
    public ApiResponse<PageResponse<Event>> list(
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "categoryId", required = false) Long categoryId,
            @RequestParam(name = "page", defaultValue = "1") long page,
            @RequestParam(name = "size", defaultValue = "10") long size) {
        if (categoryId != null && categoryId <= 0) {
            throw new BusinessException("INVALID_PARAM", "分类ID必须大于0");
        }
        // 检查分页参数，避免无效页码或过大的单页请求
        if (page < 1 || size < 1 || size > 100) {
            System.out.println(size);
            throw new BusinessException(
                    "INVALID_PAGE_PARAM",
                    "页码必须大于等于1，每页条数必须在1到100之间"
            );
        }

        Page<Event> result =
                eventService.listOnSale(city, keyword, categoryId, page, size);

        // 将框架分页对象转换为我们自己定义的响应格式
        PageResponse<Event> response = new PageResponse<>(
                result.getRecords(),
                result.getTotal(),
                result.getCurrent(),
                result.getSize(),
                result.getPages()
        );

        return ApiResponse.success(response);
    }
}
