package com.example.damai.modules.ticket.redis.service;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.ticket.redis.StockReservationResult;
import com.example.damai.modules.ticket.redis.TicketRedisKeys;
import com.example.damai.modules.ticket.redis.TicketStockScriptExecutor;
import com.example.damai.modules.ticket.service.TicketStockReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.data.redis.core.HashOperations;

import java.util.HashMap;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class TicketStockReservationAsyncServiceTest {

    @Mock
    private TicketStockScriptExecutor executor;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private TicketStockReservationService service;

    @Mock
    private HashOperations<String, Object, Object> requestHash;

    private OrderCreateMessage message() {
        return new OrderCreateMessage(
                10001L,
                UUID.randomUUID().toString(),
                90001L,
                2,
                new BigDecimal("199.00"),
                LocalDateTime.of(2027, 1, 1, 20, 0),
                LocalDateTime.of(2026, 10, 22, 17, 0)
        );
    }

    private void mockLuaResult(long result) {
        when(executor.reserveAsync(
                anyString(),
                anyString(),
                anyString(),
                any(OrderCreateMessage.class)
        )).thenReturn(result);
    }

    @Test
    void shouldReturnReservedNow() {
        OrderCreateMessage message = message();
        mockLuaResult(0L);

        assertEquals(
                StockReservationResult.RESERVED_NOW,
                service.reserveAsync(message)
        );

        verify(executor).reserveAsync(
                eq(TicketRedisKeys.stock(90001L)),
                eq(TicketRedisKeys.request(
                        message.userId(),
                        message.requestId()
                )),
                eq(TicketRedisKeys.orderCreateOutboxStream()),
                same(message)
        );
    }

    @Test
    void shouldRecognizeDuplicateRequest() {
        mockLuaResult(5L);

        assertEquals(
                StockReservationResult.RESERVED_BEFORE,
                service.reserveAsync(message())
        );
    }

    @Test
    void shouldRejectInsufficientStock() {
        mockLuaResult(2L);

        assertThrows(
                BusinessException.class,
                () -> service.reserveAsync(message())
        );
    }

    @Test
    void shouldRejectStreamWriteFailure() {
        mockLuaResult(10L);

        assertThrows(
                IllegalStateException.class,
                () -> service.reserveAsync(message())
        );
    }

    @Test
    void shouldRejectCrossModeRequest() {
        mockLuaResult(11L);

        assertThrows(
                BusinessException.class,
                () -> service.reserveAsync(message())
        );
    }
    @Test
    void shouldReadExistingAsyncRequestOnlyOnce() {
        OrderCreateMessage message = message();
        mockReservedRequest(message);

        StockReservationResult result = service.readAsyncRequestState(
                message.userId(),
                message.requestId(),
                message.ticketCategoryId(),
                message.quantity()
        );

        // 原购买已经占票，重试应恢复处理中状态。
        assertEquals(StockReservationResult.RESERVED_BEFORE, result);

        String requestKey = TicketRedisKeys.request(
                message.userId(), message.requestId()
        );

        // 状态与受理模式应来自同一次读取。
        verify(requestHash).entries(requestKey);
        verifyNoMoreInteractions(requestHash);

        // 查询原购买结果时，不能再次预扣或补偿库存。
        verifyNoInteractions(executor);
    }

    @Test
    void shouldRejectChangedPurchaseQuantity() {
        OrderCreateMessage message = message();
        mockReservedRequest(message);

        // 同一购买标识已经对应原数量，重试时不能增加购买数量。
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.readAsyncRequestState(
                        message.userId(),
                        message.requestId(),
                        message.ticketCategoryId(),
                        message.quantity() + 1
                )
        );

        assertEquals("REQUEST_PARAM_CONFLICT", exception.getCode());
    }

    @Test
    void shouldRejectRequestWithoutAsyncMode() {
        OrderCreateMessage message = message();
        Map<Object, Object> record = mockReservedRequest(message);

        // 模拟已有预扣记录，但它不是由异步流程受理的购买。
        record.remove("mode");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.readAsyncRequestState(
                        message.userId(),
                        message.requestId(),
                        message.ticketCategoryId(),
                        message.quantity()
                )
        );

        assertEquals("REQUEST_MODE_CONFLICT", exception.getCode());
    }

    private Map<Object, Object> mockReservedRequest(
            OrderCreateMessage message) {

        Map<Object, Object> record = new HashMap<>();
        record.put("ticketCategoryId", message.ticketCategoryId().toString());
        record.put("quantity", message.quantity().toString());
        record.put("status", "RESERVED");
        record.put("mode", "ASYNC");

        String requestKey = TicketRedisKeys.request(
                message.userId(), message.requestId()
        );

        when(redisTemplate.opsForHash()).thenReturn(requestHash);
        when(requestHash.entries(requestKey)).thenReturn(record);

        return record;
    }
}