package com.example.damai.modules.order.controller.admin;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.common.response.ApiResponse;
import com.example.damai.common.response.PageResponse;
import com.example.damai.modules.order.response.OrderResponse;
import com.example.damai.modules.order.response.OrderResponseAssembler;
import com.example.damai.modules.order.service.admin.AdminOrderService;
import com.example.damai.modules.order.entity.TicketOrder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员订单接口，由 SecurityConfig 校验 ADMIN 权限。
 */
@RestController
@RequestMapping("/admin/orders")
public class AdminOrderController {

    private final AdminOrderService adminOrderService;
    private final OrderResponseAssembler orderResponseAssembler;

    public AdminOrderController(
            AdminOrderService adminOrderService,
            OrderResponseAssembler orderResponseAssembler) {
        this.adminOrderService = adminOrderService;
        this.orderResponseAssembler = orderResponseAssembler;
    }

    /**
     * 管理员分页查询订单，补充关联展示信息。
     */
    @GetMapping
    public ApiResponse<PageResponse<OrderResponse>> list(
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "status", required = false) Integer status,
            @RequestParam(name = "page", defaultValue = "1") long page,
            @RequestParam(name = "size", defaultValue = "10") long size) {

        PageResponse<TicketOrder> result =
                adminOrderService.list(userId, status, page, size);

        return ApiResponse.success(new PageResponse<>(
                orderResponseAssembler.assembleList(result.records()),
                result.total(),
                result.page(),
                result.size(),
                result.pages()
        ));
    }

    /**
     * 管理员查询指定订单详情
     */
    @GetMapping("/{id}")
    public ApiResponse<OrderResponse> getById(
            @PathVariable("id") Long id) {

        TicketOrder order = adminOrderService.getById(id);

        return ApiResponse.success(
                orderResponseAssembler.assemble(order)
        );
    }
}
