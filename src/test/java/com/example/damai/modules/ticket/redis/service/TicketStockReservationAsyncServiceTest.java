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

@ExtendWith(MockitoExtension.class)
class TicketStockReservationAsyncServiceTest {

    @Mock
    private TicketStockScriptExecutor executor;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private TicketStockReservationService service;

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
}