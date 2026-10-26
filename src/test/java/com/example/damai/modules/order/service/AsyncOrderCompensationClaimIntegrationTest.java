
package com.example.damai.modules.order.service;

import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.ticket.redis.TicketRedisKeys;

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

/**
 * 异步订单补偿资格的真实事务集成测试。
 *
 * 真实组件：
 * - MySQL
 * - Redis
 * - Guard Mapper
 * - CompensationClaimService
 *
 * 模拟组件：
 * - TicketOrderService订单查询
 *
 * 不操作实际票档库存。
 */
@SpringBootTest(properties = {
        "app.order.relay.enabled=false",
        "app.order.consumer.enabled=false",
        "app.order.recovery.enabled=false",
        "app.rabbitmq.init-on-startup=false"
})
class AsyncOrderCompensationClaimIntegrationTest {

    private static final Long USER_ID = 909090909091L;

    private static final Long CATEGORY_ID = 90001L;

    private static final String OUTBOX_ID = "12345-0";

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("Asia/Shanghai");

    @Autowired
    private AsyncOrderCompensationClaimService claimService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 只模拟订单查询。
     * Guard Mapper和Spring事务都是真实的。
     */
    @MockitoBean
    private TicketOrderService orderService;

    private String requestId;
    private String requestKey;

    private OrderCreateMessage message;

    @BeforeEach
    void setUp() {

        requestId = UUID.randomUUID().toString();

        requestKey = TicketRedisKeys.request(
                USER_ID,
                requestId
        );

        LocalDateTime now =
                LocalDateTime.now(BUSINESS_ZONE);

        message = new OrderCreateMessage(
                USER_ID,
                requestId,
                CATEGORY_ID,
                2,
                new BigDecimal("399.00"),
                now.minusMinutes(5),
                now.minusMinutes(20)
        );

        // 只创建本次测试的Redis请求记录
        redisTemplate.opsForHash().putAll(
                requestKey,
                Map.of(
                        "mode", "ASYNC",
                        "status", "RESERVED",
                        "outboxId", OUTBOX_ID,
                        "ticketCategoryId",
                        CATEGORY_ID.toString(),
                        "quantity", "2"
                )
        );
    }

    /**
     * 查询真实MySQL中的Guard记录。
     */
    private int guardCount() {

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM tr_order_async_guard
                WHERE user_id = ?
                  AND request_id = ?
                """,
                Integer.class,
                USER_ID,
                requestId
        );

        return count == null ? 0 : count;
    }

    /**
     * 查询Guard状态。
     */
    private Integer guardStatus() {

        return jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM tr_order_async_guard
                WHERE user_id = ?
                  AND request_id = ?
                """,
                Integer.class,
                USER_ID,
                requestId
        );
    }

    @AfterEach
    void cleanup() {

        // 删除本次测试的Guard
        jdbcTemplate.update(
                """
                DELETE FROM tr_order_async_guard
                WHERE user_id = ?
                  AND request_id = ?
                """,
                USER_ID,
                requestId
        );

        // 删除本次测试的Redis请求记录
        redisTemplate.delete(requestKey);
    }

    /**
     * 测试1：
     * Guard插入成功后发现订单存在，
     * 必须回滚Guard。
     */
    @Test
    void shouldRollbackGuardWhenOrderExists() {

        assertEquals(0, guardCount());

        // 模拟MySQL已经存在原订单
        TicketOrder existing = new TicketOrder();
        existing.setId(10086L);

        when(orderService.findByRequestId(
                USER_ID,
                requestId
        )).thenReturn(existing);

        // Service必须拒绝补偿并触发事务回滚
        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> claimService.claimForExpiredEvent(
                                message,
                                OUTBOX_ID
                        )
                );

        assertTrue(
                exception.getMessage().contains("MySQL已有订单")
        );

        // 核心断言：真实MySQL不能留下Guard
        assertEquals(
                0,
                guardCount(),
                "补偿资格事务回滚后不能保留Guard"
        );

        // Redis原预扣记录不能被修改
        assertEquals(
                "RESERVED",
                redisTemplate.opsForHash()
                        .get(requestKey, "status")
        );
    }

    /**
     * 测试2：
     * 订单不存在，Guard成功提交为2。
     */
    @Test
    void shouldCommitCompensationClaim() {

        assertEquals(0, guardCount());

        // Mockito默认返回null，模拟暂无订单
        CompensationClaimResult result =
                claimService.claimForExpiredEvent(
                        message,
                        OUTBOX_ID
                );

        assertEquals(
                CompensationClaimResult.CLAIMED,
                result
        );

        // 独立查询真实MySQL：
        // 确认Spring事务已经提交
        assertEquals(1, guardCount());

        assertEquals(2, guardStatus());

        // 这里只申请资格，不能提前补Redis
        assertEquals(
                "RESERVED",
                redisTemplate.opsForHash()
                        .get(requestKey, "status")
        );
    }
}
