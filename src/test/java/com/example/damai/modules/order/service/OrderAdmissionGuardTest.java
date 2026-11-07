package com.example.damai.modules.order.service;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.order.dto.OrderCreateRequest;
import com.example.damai.modules.order.mapper.TicketOrderMapper;
import com.example.damai.modules.order.ratelimit.OrderAdmissionGuard;
import com.example.damai.modules.order.ratelimit.OrderBacklogGuard;
import com.example.damai.modules.order.ratelimit.PurchaseRateLimiter;
import com.example.damai.modules.ticket.service.TicketStockReservationService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class OrderAdmissionGuardTest {

    private final TicketOrderMapper orderMapper =
            mock(TicketOrderMapper.class);

    private final TicketOrderService orderService =
            mock(TicketOrderService.class);

    private final TicketStockReservationService reservationService =
            mock(TicketStockReservationService.class);

    private final PurchaseRateLimiter rateLimiter =
            mock(PurchaseRateLimiter.class);

    private final OrderBacklogGuard backlogGuard =
            mock(OrderBacklogGuard.class);

    // 故意只设置一个名额，准确制造名额耗尽的场景。
    private final OrderAdmissionGuard admissionGuard =
            new OrderAdmissionGuard(1);

    private final OrderSubmissionService service =
            new OrderSubmissionService(
                    orderMapper,
                    orderService,
                    reservationService,
                    rateLimiter,
                    admissionGuard,
                    backlogGuard
            );

    @Test
    void fullCapacityShouldRejectNewPurchase() {
        OrderCreateRequest request = newRequest();

        // 模拟另一个请求正在受理，占用了唯一名额。
        admissionGuard.enter();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.submitAsync(607L, request)
        );

        assertEquals("SYSTEM_BUSY", exception.getCode());

        // 被系统保护拒绝后，不再竞争令牌、
        // 校验购买条件或操作MySQL订单。
        verifyNoInteractions(
                rateLimiter,
                orderService,
                orderMapper
        );

        // 只允许前面的原请求查询，不能执行库存预扣。
        verify(reservationService).readAsyncRequestState(
                607L, request.getRequestId(), 16L, 1
        );
        verifyNoMoreInteractions(reservationService);

        // 本次调用没有取得名额，就不能归还名额。
        // 此时原来的唯一名额应该仍被占用。
        assertThrows(
                BusinessException.class,
                admissionGuard::enter
        );

        // 模拟原来占用名额的请求结束。
        admissionGuard.leave();
    }

    @Test
    void purchaseValidationFailureShouldReleaseCapacity() {
        OrderCreateRequest request = newRequest();

        // 模拟取得名额、通过频率检查后，
        // 购买校验发现演出已经开始。
        when(orderService.validatePurchase(request))
                .thenThrow(new BusinessException(
                        "SALE_ENDED",
                        "演出已开始，停止售票"
                ));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.submitAsync(607L, request)
        );

        assertEquals("SALE_ENDED", exception.getCode());

        // 确认请求确实进入了新购买处理流程。
        verify(rateLimiter).check(607L, 16L);

        // 即使购买被拒绝，受理名额也必须归还，
        // 后续请求才能继续进入。
        assertDoesNotThrow(admissionGuard::enter);
        admissionGuard.leave();

        // 校验失败后没有执行库存预扣或MySQL建单。
        verify(reservationService).readAsyncRequestState(
                607L, request.getRequestId(), 16L, 1
        );
        verifyNoMoreInteractions(reservationService);
        verifyNoInteractions(orderMapper);
        verify(backlogGuard).check();
    }


    @Test
    void purchaseValidationInProgressShouldKeepCapacity() throws Exception {
        OrderCreateRequest firstRequest = newRequest();
        OrderCreateRequest secondRequest = newRequest();

        CountDownLatch validationStarted = new CountDownLatch(1);
        CountDownLatch allowValidationToFinish = new CountDownLatch(1);

        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            when(orderService.validatePurchase(any(OrderCreateRequest.class)))
                    .thenAnswer(invocation -> {
                        OrderCreateRequest current = invocation.getArgument(0);

                        if (current == firstRequest) {
                            // 第一笔购买已经进入校验，仍在占用唯一受理名额。
                            validationStarted.countDown();

                            assertTrue(
                                    allowValidationToFinish.await(5, TimeUnit.SECONDS),
                                    "等待测试放行购买校验超时"
                            );
                        }

                        // 校验结束后拒绝购买，验证异常退出也能归还名额。
                        throw new BusinessException(
                                "SALE_ENDED",
                                "演出已开始，停止售票"
                        );
                    });

            Future<?> firstCall = worker.submit(() -> {
                BusinessException exception = assertThrows(
                        BusinessException.class,
                        () -> service.submitAsync(607L, firstRequest)
                );

                assertEquals("SALE_ENDED", exception.getCode());
            });

            // 确认第一笔正在校验，再让另一个用户发起新的购买。
            assertTrue(
                    validationStarted.await(5, TimeUnit.SECONDS),
                    "第一笔购买未进入校验阶段"
            );

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> service.submitAsync(608L, secondRequest)
            );

            // 第一笔尚未结束，第二笔应因没有受理名额而被拒绝。
            assertEquals("SYSTEM_BUSY", exception.getCode());
            verify(orderService, never()).validatePurchase(secondRequest);

            // 放行第一笔，等待它完成异常处理和名额释放。
            allowValidationToFinish.countDown();
            firstCall.get(5, TimeUnit.SECONDS);

            // 第一笔结束后，后续购买应能重新取得名额。
            assertDoesNotThrow(admissionGuard::enter);
            admissionGuard.leave();

        } finally {
            // 即使断言失败，也要放行后台线程并关闭线程池。
            allowValidationToFinish.countDown();
            worker.shutdownNow();
        }
    }

    private OrderCreateRequest newRequest() {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setTicketCategoryId(16L);
        request.setQuantity(1);
        return request;
    }
}