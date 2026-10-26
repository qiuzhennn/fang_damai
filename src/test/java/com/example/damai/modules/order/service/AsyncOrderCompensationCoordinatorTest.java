
package com.example.damai.modules.order.service;

import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.order.mq.OrderRecoveryDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AsyncOrderCompensationCoordinatorTest {

    private static final String OUTBOX_ID = "12345-0";

    @Mock
    private AsyncOrderCompensationClaimService claimService;

    @Mock
    private AsyncOrderCompensationService compensationService;

    @Mock
    private OrderDeadLetterRecoveryService recoveryService;

    @InjectMocks
    private AsyncOrderCompensationCoordinator coordinator;

    private OrderCreateMessage message() {

        LocalDateTime start =
                LocalDateTime.of(2026, 10, 22, 20, 0);

        return new OrderCreateMessage(
                10001L,
                UUID.randomUUID().toString(),
                90001L,
                2,
                new BigDecimal("399.00"),
                start,
                start.minusMinutes(10)
        );
    }

    @Test
    void shouldCompensateAfterClaim() {

        OrderCreateMessage message = message();

        when(claimService.claimForExpiredEvent(
                message, OUTBOX_ID
        )).thenReturn(CompensationClaimResult.CLAIMED);

        var result = coordinator.recoverExpiredRequest(
                message, OUTBOX_ID
        );

        assertEquals(
                AsyncOrderCompensationCoordinator.Result.COMPENSATED,
                result
        );

        InOrder order = inOrder(
                claimService, compensationService
        );

        order.verify(claimService)
                .claimForExpiredEvent(message, OUTBOX_ID);

        order.verify(compensationService)
                .completeClaimedCompensation(message, OUTBOX_ID);
    }

    @Test
    void shouldResumeAlreadyCompensatedRedis() {

        OrderCreateMessage message = message();

        when(claimService.claimForExpiredEvent(
                message, OUTBOX_ID
        )).thenReturn(
                CompensationClaimResult.ALREADY_CLAIMED
        );

        var result = coordinator.recoverExpiredRequest(
                message, OUTBOX_ID
        );

        assertEquals(
                AsyncOrderCompensationCoordinator.Result.COMPENSATED,
                result
        );

        verify(compensationService)
                .completeClaimedCompensation(message, OUTBOX_ID);
    }

    @Test
    void shouldNeverCompensateCreatedOrder() {

        OrderCreateMessage message = message();

        when(claimService.claimForExpiredEvent(
                message, OUTBOX_ID
        )).thenReturn(
                CompensationClaimResult.ORDER_CREATED
        );

        when(recoveryService.reconcile(
                message, OUTBOX_ID
        )).thenReturn(OrderRecoveryDecision.ALREADY_CREATED);

        var result = coordinator.recoverExpiredRequest(
                message, OUTBOX_ID
        );

        assertEquals(
                AsyncOrderCompensationCoordinator.Result.ORDER_CREATED,
                result
        );

        verifyNoInteractions(compensationService);
    }
}
