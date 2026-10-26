package com.example.damai.modules.ticket.controller.admin;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.common.response.ApiResponse;
import com.example.damai.modules.ticket.dto.TicketCategoryCreateRequest;
import com.example.damai.modules.ticket.service.TicketCategoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.example.damai.common.response.PageResponse;
import com.example.damai.modules.ticket.service.admin.AdminTicketCategoryQueryService;

/** 管理员写操作；业务校验继续由原有 Service 执行。 */
@RestController
@RequestMapping("/admin/ticket-categories")
public class AdminTicketCategoryController {
    private final TicketCategoryService ticketCategoryService;
    private final AdminTicketCategoryQueryService service;

    public AdminTicketCategoryController(TicketCategoryService ticketCategoryService, AdminTicketCategoryQueryService service) {
        this.ticketCategoryService = ticketCategoryService;
        this.service = service;
    }

    @PostMapping
    public ApiResponse<Boolean> create(
            @Valid @RequestBody TicketCategoryCreateRequest request) {

        return ApiResponse.success(ticketCategoryService.create(request)
        );
    }
    @GetMapping
    public ApiResponse<PageResponse<AdminTicketCategoryQueryService.CategoryRow>> categories(
            @RequestParam(name = "sessionId", required = false) Long sessionId,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", defaultValue = "1") long page,
            @RequestParam(name = "size", defaultValue = "10") long size) {
        validatePage(page, size);
        validateId(sessionId);
        return ApiResponse.success(service.categories(sessionId, keyword, page, size));
    }

    private void validatePage(long page, long size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException("INVALID_PAGE_PARAM",
                    "页码必须大于等于1，每页条数必须在1到100之间");
        }
    }

    private void validateId(Long id) {
        if (id != null && id <= 0) {
            throw new BusinessException("INVALID_PARAM", "关联ID必须大于0");
        }
    }
}
