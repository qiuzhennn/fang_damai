package com.example.damai.modules.program.controller.admin;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.common.response.ApiResponse;
import com.example.damai.modules.program.dto.EventSessionCreateRequest;
import com.example.damai.modules.program.service.EventSessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.example.damai.common.response.PageResponse;
import com.example.damai.modules.program.service.admin.AdminProgramQueryService;

/** 管理员写操作；业务校验继续由原有 Service 执行。 */
@RestController
@RequestMapping("/admin/event-sessions")
public class AdminEventSessionController {
    private final EventSessionService eventSessionService;
    private final AdminProgramQueryService service;

    public AdminEventSessionController(EventSessionService eventSessionService, AdminProgramQueryService service) {
        this.eventSessionService = eventSessionService;
        this.service = service;
    }

    @PostMapping
    public ApiResponse<Boolean> create(
            @Valid @RequestBody EventSessionCreateRequest request) {

        return ApiResponse.success(
                eventSessionService.create(request)
        );
    }

    @PutMapping("/{id}/enable-sale")
    public ApiResponse<Boolean> enableSale(
            @PathVariable("id") Long id) {

        if (id <= 0) {
            throw new BusinessException(
                    "INVALID_PARAM", "场次ID必须大于0");
        }

        return ApiResponse.success(
                eventSessionService.enableSale(id)
        );
    }

    @PutMapping("/{id}/disable-sale")
    public ApiResponse<Boolean> disableSale(
            @PathVariable("id") Long id) {

        if (id <= 0) {
            throw new BusinessException(
                    "INVALID_PARAM", "场次ID必须大于0");
        }

        return ApiResponse.success(
                eventSessionService.disableSale(id)
        );
    }
    @GetMapping
    public ApiResponse<PageResponse<AdminProgramQueryService.SessionRow>> sessions(
            @RequestParam(name = "eventId", required = false) Long eventId,
            @RequestParam(name = "status", required = false) Integer status,
            @RequestParam(name = "page", defaultValue = "1") long page,
            @RequestParam(name = "size", defaultValue = "10") long size) {
        validatePage(page, size);
        validateId(eventId);
        validateStatus(status);
        return ApiResponse.success(service.sessions(eventId, status, page, size));
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

    private void validateStatus(Integer status) {
        if (status != null && (status < 0 || status > 2)) {
            throw new BusinessException("INVALID_PARAM", "状态只能为0、1或2");

        }
    }

}
