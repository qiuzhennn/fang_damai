
package com.example.damai.modules.order.service;

import com.example.damai.modules.order.mapper.AsyncOrderGuardMapper;
import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.ticket.redis.TicketRedisKeys;
import com.example.damai.modules.ticket.service.TicketStockReservationService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 异步订单补偿跨组件恢复集成测试。
 *
 * 使用真实：
 * 1. MySQL Guard Mapper
 * 2. Redis请求状态
 * 3. Spring事务
 * 4. 补偿资格申请Service
 * 5. 补偿执行Service
 * 6. 补偿协调器
 *
 * 模拟：
 * 1. MySQL订单查询
 * 2. Redis库存补偿动作
 *
 * 不修改实际演出票档的库存。
 */
@SpringBootTest(properties = {
        "app.order.relay.enabled=false",
        "app.order.consumer.enabled=false",
        "app.order.recovery.enabled=false",
        "app.rabbitmq.init-on-startup=false"
})
class AsyncOrderCompensationRecoveryIntegrationTest {

    private static final Long USER_ID = 909090909093L;

    private static final Long CATEGORY_ID = 90001L;

    private static final int QUANTITY = 2;

    private static final String OUTBOX_ID = "12345-0";

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("Asia/Shanghai");

    @Autowired
    private AsyncOrderCompensationCoordinator coordinator;

    @Autowired
    private AsyncOrderGuardMapper guardMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // 只替换订单查询，不影响真实Guard和事务
    @MockitoBean
    private TicketOrderService orderService;

    // 禁止测试实际执行库存回补
    @MockitoBean
    private TicketStockReservationService reservationService;

    private OrderCreateMessage message;

    private String requestId;

    private String requestKey;

    @BeforeEach
    void setUp() {

        requestId = UUID.randomUUID().toString();

        LocalDateTime now =
                LocalDateTime.now(BUSINESS_ZONE);

        message = new OrderCreateMessage(
                USER_ID,
                requestId,
                CATEGORY_ID,
                QUANTITY,
                new BigDecimal("399.00"),
                now.minusMinutes(5),
                now.minusMinutes(20)
        );

        requestKey = TicketRedisKeys.request(
                USER_ID,
                requestId
        );
    }

    /**
     * 在真实MySQL中创建一个已提交的补偿资格。
     *
     * Guard=2。
     */
    private void createCompensationGuard() {

        int rows = guardMapper.claimCompensation(
                message.userId(),
                message.requestId(),
                message.ticketCategoryId(),
                message.quantity(),
                OUTBOX_ID,
                message.unitPrice(),
                message.eventStartTime()
        );

        assertEquals(1, rows);

        assertEquals(
                2,
                guardMapper.findStatus(
                        USER_ID,
                        requestId
                )
        );
    }

    /**
     * 在真实Redis中模拟指定的请求状态。
     *
     * 不操作库存Key。
     */
    private void createRedisState(String status) {

        redisTemplate.opsForHash().putAll(
                requestKey,
                Map.of(
                        "mode", "ASYNC",
                        "status", status,
                        "outboxId", OUTBOX_ID,
                        "ticketCategoryId",
                        CATEGORY_ID.toString(),
                        "quantity",
                        Integer.toString(QUANTITY)
                )
        );
    }

    /**
     * 测试1：
     *
     * Redis库存已经补偿成功，
     * 但是程序崩溃，Guard还保持2。
     *
     * 恢复时只更新Guard，不重复执行Redis补偿。
     */
    @Test
    void shouldRecoverAfterRedisCompensatedButGuardNotFinished() {

        // 1. 创建已提交的MySQL补偿资格
        createCompensationGuard();

        // 2. 模拟Lua已成功执行：
        // Redis请求状态已经变成COMPENSATED
        //
        // 此处直接设置状态，是为了模拟崩溃后的快照。
        // 不实际执行库存加回。
        createRedisState("COMPENSATED");

        // 3. 调用真实补偿协调器
        AsyncOrderCompensationCoordinator.Result result =
                coordinator.recoverExpiredRequest(
                        message,
                        OUTBOX_ID
                );

        // 4. 应确认补偿已经完成
        assertEquals(
                AsyncOrderCompensationCoordinator.Result.COMPENSATED,
                result
        );

        // 5. MySQL Guard应从2恢复为3
        assertEquals(
                3,
                guardMapper.findStatus(
                        USER_ID,
                        requestId
                )
        );

        // 6. Redis状态仍为COMPENSATED
        assertEquals(
                "COMPENSATED",
                redisTemplate.opsForHash()
                        .get(requestKey, "status")
        );

        // 7. 已补偿的请求不能再次调用库存回补
        verify(reservationService, never()).compensate(
                anyLong(),
                anyString(),
                anyLong(),
                anyInt()
        );

        // 8. 再次调用协调器，模拟重复恢复
        AsyncOrderCompensationCoordinator.Result second =
                coordinator.recoverExpiredRequest(
                        message,
                        OUTBOX_ID
                );

        assertEquals(
                AsyncOrderCompensationCoordinator.Result.COMPENSATED,
                second
        );

        // Guard保持3
        assertEquals(
                3,
                guardMapper.findStatus(
                        USER_ID,
                        requestId
                )
        );

        // 重复恢复也不允许再次执行补偿
        verify(reservationService, never()).compensate(
                anyLong(),
                anyString(),
                anyLong(),
                anyInt()
        );
    }

    /**
     * 测试2：
     *
     * 已获得Guard补偿资格，
     * 但调用Redis补偿时发生故障。
     *
     * Guard必须继续保持2，
     * 不能错误标记为补偿完成。
     */
    @Test
    void shouldKeepGuardPendingWhenRedisCompensationFails() {

        // 1. 创建MySQL补偿资格
        createCompensationGuard();

        // 2. Redis仍处于预扣状态
        createRedisState("RESERVED");

        // 3. 故意模拟Redis补偿调用失败
        doThrow(
                new IllegalStateException(
                        "SIMULATED_REDIS_FAILURE"
                )
        ).when(reservationService).compensate(
                eq(USER_ID),
                eq(requestId),
                eq(CATEGORY_ID),
                eq(QUANTITY)
        );

        // 4. 协调器应向上抛异常
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> coordinator.recoverExpiredRequest(
                        message,
                        OUTBOX_ID
                )
        );

        assertTrue(
                exception.getMessage()
                        .contains("SIMULATED_REDIS_FAILURE")
        );

        // 5. Guard仍应保持2
        assertEquals(
                2,
                guardMapper.findStatus(
                        USER_ID,
                        requestId
                )
        );

        // 6. Redis仍处于RESERVED
        assertEquals(
                "RESERVED",
                redisTemplate.opsForHash()
                        .get(requestKey, "status")
        );

        // 7. 确认确实尝试执行过补偿
        verify(reservationService, times(1)).compensate(
                USER_ID,
                requestId,
                CATEGORY_ID,
                QUANTITY
        );
    }

    @AfterEach
    void cleanup() {

        // 只清理本次随机requestId对应的Guard
        if (requestId != null) {

            jdbcTemplate.update(
                    """
                    DELETE FROM tr_order_async_guard
                    WHERE user_id = ?
                      AND request_id = ?
                    """,
                    USER_ID,
                    requestId
            );
        }

        // 只删除本次测试创建的Redis请求Key
        if (requestKey != null) {
            redisTemplate.delete(requestKey);
        }
    }
}
