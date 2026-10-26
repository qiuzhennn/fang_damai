package com.example.damai.modules.ticket.controller.publicapi;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.common.response.ApiResponse;
import com.example.damai.modules.ticket.entity.TicketCategory;
import com.example.damai.modules.ticket.service.TicketCategoryService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 公开浏览接口；仅提供原有查询，不包含管理写操作。 */
@RestController
@RequestMapping("/public/ticket-categories")
public class PublicTicketCategoryController {
    private final TicketCategoryService ticketCategoryService;

    public PublicTicketCategoryController(TicketCategoryService ticketCategoryService) {
        this.ticketCategoryService = ticketCategoryService;
    }

    @GetMapping
    public ApiResponse<List<TicketCategory>> listBySessionId(
            @RequestParam("sessionId") Long sessionId) {

        if (sessionId <= 0) {
            throw new BusinessException(
                    "INVALID_PARAM", "场次ID必须大于0");
        }

        return ApiResponse.success(
                ticketCategoryService.listBySessionId(sessionId)
        );
    }
}
