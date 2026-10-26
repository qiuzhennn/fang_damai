package com.example.damai.modules.order.service;

import com.example.damai.modules.order.entity.AsyncOrderGuardSnapshot;
import com.example.damai.modules.order.mq.OrderCreateMessage;

import java.util.Objects;

/**
 * 验证异步订单消息与MySQL Guard快照是否一致。
 */
public final class AsyncOrderGuardVerifier {

    private AsyncOrderGuardVerifier() {
    }

    public static boolean matches(
            OrderCreateMessage message,
            String outboxId,
            AsyncOrderGuardSnapshot guard) {

        if (message == null || guard == null) {
            return false;
        }

        return Objects.equals(guard.userId(), message.userId())
                && Objects.equals(guard.requestId(), message.requestId())
                && Objects.equals(
                guard.ticketCategoryId(),
                message.ticketCategoryId()
        )
                && Objects.equals(
                guard.quantity(),
                message.quantity()
        )
                && Objects.equals(guard.outboxId(), outboxId)
                && guard.unitPrice() != null
                && message.unitPrice() != null
                && guard.unitPrice()
                .compareTo(message.unitPrice()) == 0
                && Objects.equals(
                guard.eventStartTime(),
                message.eventStartTime()
        );
    }
}