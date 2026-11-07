package com.example.damai.modules.order.mq;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderCreateMessageTest {

    private static final LocalDateTime EVENT_START =
            LocalDateTime.of(2020, 1, 1, 20, 0);

    @Test
    void pastEventShouldNotInvalidateAcceptedSnapshot() {
        // 原购买在演出开始前受理，之后仍需使用这份资料核对订单或补偿。
        OrderCreateMessage message = message(
                "399.00",
                EVENT_START.minusMinutes(10)
        );

        assertTrue(
                message.hasValidFields(),
                "演出已经开始，不应使原本合法的购买资料快照失效"
        );
    }

    @Test
    void priceShouldFollowOrderAmountConstraints() {
        LocalDateTime acceptedAt = EVENT_START.minusMinutes(10);

        assertAll(
                () -> assertTrue(
                        message("0.00", acceptedAt).hasValidFields(),
                        "允许零元票"
                ),
                () -> assertTrue(
                        message("9999999999.99", acceptedAt).hasValidFields(),
                        "允许合法的单价上限"
                ),
                () -> assertFalse(
                        message("-0.01", acceptedAt).hasValidFields(),
                        "单价不能为负数"
                ),
                () -> assertFalse(
                        message("0.001", acceptedAt).hasValidFields(),
                        "单价不能超过两位小数"
                ),
                () -> assertFalse(
                        message("10000000000.00", acceptedAt).hasValidFields(),
                        "单价不能超过金额上限"
                )
        );
    }

    @Test
    void acceptanceMustBeBeforeEventStart() {
        assertAll(
                () -> assertFalse(
                        message("399.00", EVENT_START).hasValidFields(),
                        "演出开始时已经停止受理购买"
                ),
                () -> assertFalse(
                        message("399.00", EVENT_START.plusMinutes(1))
                                .hasValidFields(),
                        "演出开始后受理的购买不能成为合法快照"
                )
        );
    }

    private OrderCreateMessage message(
            String price,
            LocalDateTime acceptedAt) {

        return new OrderCreateMessage(
                607L,
                UUID.randomUUID().toString(),
                16L,
                2,
                new BigDecimal(price),
                EVENT_START,
                acceptedAt
        );
    }
}