
package com.example.damai.modules.order.service;

import com.example.damai.modules.order.entity.AsyncOrderGuardSnapshot;
import com.example.damai.modules.order.mapper.AsyncOrderGuardMapper;
import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.ticket.service.TicketStockReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AsyncOrderCompensationServiceTest {

    private static final String OUTBOX_ID = "12345-0";

    @Mock
    private AsyncOrderGuardMapper guardMapper;

    @Mock
    private TicketOrderService orderService;

    @Mock
    private TicketStockReservationService reservationService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @InjectMocks
    private AsyncOrderCompensationService compensationService;

    private OrderCreateMessage message() {
        return new OrderCreateMessage(
                10001L,
                UUID.randomUUID().toString(),
                90001L,
                2,
                new BigDecimal("399.00"),
                LocalDateTime.of(2027, 1, 1, 20, 0),
                LocalDateTime.of(2026, 10, 23, 10, 0)
        );
    }

    private Map<Object, Object> redisState(String status) {

        Map<Object, Object> state = new HashMap<>();

        state.put("mode", "ASYNC");
        state.put("outboxId", OUTBOX_ID);
        state.put("ticketCategoryId", "90001");
        state.put("quantity", "2");
        state.put("status", status);

        return state;
    }


    private void mockGuard(
            OrderCreateMessage message,
            int status) {

        AsyncOrderGuardSnapshot snapshot =
                new AsyncOrderGuardSnapshot(
                        message.userId(),
                        message.requestId(),
                        message.ticketCategoryId(),
                        message.quantity(),
                        OUTBOX_ID,
                        message.unitPrice(),
                        message.eventStartTime(),
                        status,
                        null
                );

        when(guardMapper.findGuard(
                message.userId(),
                message.requestId()
        )).thenReturn(snapshot);
    }


    /**
     * Guard=2，Redis=RESERVED：
     * 应执行一次补偿并确认结果。
     */
    @Test
    void shouldCompensateReservedStock() {

        OrderCreateMessage message = message();

        mockGuard(message, 2);

        when(redisTemplate.opsForHash())
                .thenReturn(hashOperations);

        when(hashOperations.entries(anyString()))
                .thenReturn(
                        redisState("RESERVED"),
                        redisState("COMPENSATED")
                );

        when(guardMapper.finishCompensation(
                message.userId(),
                message.requestId(),
                OUTBOX_ID
        )).thenReturn(1);

        compensationService.completeClaimedCompensation(
                message, OUTBOX_ID
        );

        verify(reservationService, times(1))
                .compensate(
                        message.userId(),
                        message.requestId(),
                        message.ticketCategoryId(),
                        message.quantity()
                );

        verify(guardMapper).finishCompensation(
                message.userId(),
                message.requestId(),
                OUTBOX_ID
        );
    }

    /**
     * Redis已经补偿：
     * 只更新Guard，不重复加库存。
     */
    @Test
    void shouldOnlyFinishGuardWhenAlreadyCompensated() {

        OrderCreateMessage message = message();

        mockGuard(message, 2);

        when(redisTemplate.opsForHash())
                .thenReturn(hashOperations);

        when(hashOperations.entries(anyString()))
                .thenReturn(redisState("COMPENSATED"));

        when(guardMapper.finishCompensation(
                message.userId(),
                message.requestId(),
                OUTBOX_ID
        )).thenReturn(1);

        compensationService.completeClaimedCompensation(
                message, OUTBOX_ID
        );

        verifyNoInteractions(reservationService);

        verify(guardMapper).finishCompensation(
                message.userId(),
                message.requestId(),
                OUTBOX_ID
        );
    }

    /**
     * Guard尚未获得补偿资格：
     * 不允许执行Redis补偿。
     */
    @Test
    void shouldRejectWithoutCompensationClaim() {

        OrderCreateMessage message = message();

        mockGuard(message, 1);

        assertThrows(
                IllegalStateException.class,
                () -> compensationService
                        .completeClaimedCompensation(
                                message, OUTBOX_ID
                        )
        );

        verifyNoInteractions(reservationService);

        verify(guardMapper, never())
                .finishCompensation(
                        anyLong(),
                        anyString(),
                        anyString()
                );
    }
}
