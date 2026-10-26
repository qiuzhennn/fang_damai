package com.example.damai.modules.order.controller.user;


import com.example.damai.common.response.ApiResponse;
import com.example.damai.common.response.PageResponse;
import com.example.damai.common.security.LoginUser;
import com.example.damai.modules.order.dto.OrderCreateRequest;
import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.order.response.OrderResponse;
import com.example.damai.modules.order.response.OrderResponseAssembler;
import com.example.damai.modules.order.service.OrderSubmissionService;
import com.example.damai.modules.order.service.TicketOrderService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.example.damai.modules.ticket.redis.StockReservationResult;

/**
 * 购票订单接口。
 */
@RestController
@RequestMapping("/user/orders")
public class UserOrderController {

    private final TicketOrderService ticketOrderService;
    private final OrderResponseAssembler orderResponseAssembler;
    private final OrderSubmissionService orderSubmissionService;

    public UserOrderController(
            TicketOrderService ticketOrderService,
            OrderResponseAssembler orderResponseAssembler,
            OrderSubmissionService orderSubmissionService) {
        this.ticketOrderService = ticketOrderService;
        this.orderResponseAssembler = orderResponseAssembler;
        this.orderSubmissionService = orderSubmissionService;
    }

    /**
     * 当前登录用户下单。
     */
    @PostMapping
    public ApiResponse<Long> create(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody OrderCreateRequest request) {

        //Long creat = ticketOrderService.create(loginUser.id(), request);
        //orderSubmissionService 会调用ticketOrderService，且增加了识别重复请求，复用已有订单的逻辑。
        Long submit = orderSubmissionService.submit(loginUser.id(), request);

        return ApiResponse.success(submit);
    }

    /**
     * 查询本人订单详情
     */
    @GetMapping("/{id}")
    public ApiResponse<OrderResponse> getById(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable("id") Long id) {

        TicketOrder order =
                ticketOrderService.getById(loginUser.id(), id);

        return ApiResponse.success(
                orderResponseAssembler.assemble(order)
        );
    }

    /**
     * 取消当前用户的待支付订单。
     */
    @PutMapping("/{id}/cancel")
    public ApiResponse<Boolean> cancel(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable("id") Long id) {

        return ApiResponse.success(
                ticketOrderService.cancel(loginUser.id(), id)
        );
    }

    /**
     * 模拟支付当前用户的订单，仅用于开发测试。
     */
    @PutMapping("/{id}/mock-pay")
    public ApiResponse<Boolean> mockPay(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable("id") Long id) {

        return ApiResponse.success(
                ticketOrderService.mockPay(loginUser.id(), id)
        );
    }

    /**
     * 查询本人订单
     */
    @GetMapping
    public ApiResponse<PageResponse<OrderResponse>> list(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(name = "status", required = false) Integer status,
            @RequestParam(name = "page", defaultValue = "1") long page,
            @RequestParam(name = "size", defaultValue = "10") long size) {

        // 仍由原 Service 限定当前用户，并执行已有参数校验
        PageResponse<TicketOrder> result =
                ticketOrderService.listMyOrders(
                        loginUser.id(), status, page, size);

        return ApiResponse.success(new PageResponse<>(
                orderResponseAssembler.assembleList(result.records()),
                result.total(),
                result.page(),
                result.size(),
                result.pages()
        ));
    }

    /**
     * 查询本次购买是否已经生成订单。
     * 查不到只表示当前未查到，不能据此判定下单失败。
     */
    @GetMapping("/by-request")
    public ApiResponse<OrderResponse> findByRequestId(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam("requestId") String requestId) {

        TicketOrder order = ticketOrderService.findByRequestId(loginUser.id(), requestId);

        if (order == null) {
            // TODO: 接入Redis请求记录，进一步区分预扣处理中和已补偿状态。
            return ApiResponse.success(null);
        }

        return ApiResponse.success(orderResponseAssembler.assemble(order)
        );
    }


    /**
     * 异步抢票接口。
     *
     * 返回处理状态，不立即返回订单ID。
     */
    @PostMapping("/async")
    public ApiResponse<AsyncOrderSubmitResponse> createAsync(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody OrderCreateRequest request) {

        StockReservationResult result =
                orderSubmissionService.submitAsync(
                        loginUser.id(),
                        request
                );

        String status = switch (result) {

            // 刚受理成功或之前已经预扣
            // 都表示订单可能仍在后台创建
            case RESERVED_NOW, RESERVED_BEFORE ->
                    "PROCESSING";

            // Redis记录表明已创建订单
            case ORDER_CREATED ->
                    "ORDER_CREATED";

            // Redis记录表明该请求已退回库存
            case COMPENSATED ->
                    "COMPENSATED";
        };

        return ApiResponse.success(
                new AsyncOrderSubmitResponse(
                        request.getRequestId(),
                        status
                )
        );
    }

    /**
     * 异步抢票接口响应。
     *
     * 暂时放在Controller内部，
     * 不额外创建DTO文件。
     */
    public record AsyncOrderSubmitResponse(
            String requestId,
            String status
    ) {
    }

}
