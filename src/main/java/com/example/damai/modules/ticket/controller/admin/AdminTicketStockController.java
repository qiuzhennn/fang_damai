package com.example.damai.modules.ticket.controller.admin;

import com.example.damai.common.response.ApiResponse;
import com.example.damai.modules.ticket.service.TicketStockWarmupService;
import org.springframework.web.bind.annotation.*;

/**
 * 管理员库存预热接口，由现有 ADMIN 权限规则保护。
 */
@RestController
@RequestMapping("/admin/ticket-categories")
public class AdminTicketStockController {

    private final TicketStockWarmupService warmupService;

    public AdminTicketStockController(TicketStockWarmupService warmupService) {
        this.warmupService = warmupService;
    }

    /**
     * 初始化指定票档的 Redis 可售库存。
     */
    @PostMapping("/{id}/stock/warmup")
    public ApiResponse<Boolean> warmup(@PathVariable("id") Long id) {

        return ApiResponse.success(warmupService.warmup(id));
    }

    /**
     * 撤销指定票档的Redis库存预热。
     * 再次受理新购买前，需要重新预热并启售。
     */
    @DeleteMapping("/{id}/stock/warmup")
    public ApiResponse<Boolean> cancelWarmup(@PathVariable("id") Long id) {

        return ApiResponse.success(warmupService.cancelWarmup(id));
    }
}