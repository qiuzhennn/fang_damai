package com.example.damai.modules.order.service;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.order.dto.OrderCreateRequest;
import com.example.damai.modules.order.mapper.TicketOrderMapper;
import com.example.damai.modules.order.ratelimit.OrderAdmissionGuard;
import com.example.damai.modules.order.ratelimit.OrderBacklogGuard;
import com.example.damai.modules.order.ratelimit.PurchaseRateLimiter;
import com.example.damai.modules.ticket.redis.StockReservationResult;
import com.example.damai.modules.ticket.service.TicketStockReservationService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class OrderSubmissionRateLimitTest {

    @Test
    void acceptedRequestShouldBypassRateLimit() {
        TicketOrderMapper orderMapper = mock(TicketOrderMapper.class);
        TicketOrderService orderService = mock(TicketOrderService.class);
        TicketStockReservationService reservationService =
                mock(TicketStockReservationService.class);
        PurchaseRateLimiter rateLimiter =
                mock(PurchaseRateLimiter.class);

        OrderAdmissionGuard admissionGuard =
                mock(OrderAdmissionGuard.class);
        OrderBacklogGuard backlogGuard =
                mock(OrderBacklogGuard.class);

        OrderSubmissionService service = new OrderSubmissionService(
                orderMapper,
                orderService,
                reservationService,
                rateLimiter,
                admissionGuard,
                backlogGuard
        );

        OrderCreateRequest request = new OrderCreateRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setTicketCategoryId(16L);
        request.setQuantity(1);

        // 模拟这次购买已经预扣成功，后台仍在创建订单。
        when(reservationService.readAsyncRequestState(
                607L,
                request.getRequestId(),
                16L,
                1
        )).thenReturn(StockReservationResult.RESERVED_BEFORE);

        // 模拟用户额度已耗尽：
        // 如果业务错误地再次检查限流，就会收到拒绝异常。
        doThrow(new BusinessException(
                "USER_RATE_LIMITED",
                "操作过于频繁，请稍后重试"
        )).when(rateLimiter).check(607L, 16L);

        StockReservationResult result =
                service.submitAsync(607L, request);

        // 原购买应恢复“已受理、仍在处理”的状态。
        assertEquals(
                StockReservationResult.RESERVED_BEFORE,
                result
        );

        // 恢复原结果不竞争新额度，也不重新校验购买条件或创建订单。
        verifyNoInteractions(
                rateLimiter,
                orderService,
                orderMapper,
                admissionGuard
        );

        // 除了查询原购买，不得再次调用预扣等方法。
        verify(reservationService).readAsyncRequestState(
                607L,
                request.getRequestId(),
                16L,
                1
        );
        verifyNoMoreInteractions(reservationService);

    }
}