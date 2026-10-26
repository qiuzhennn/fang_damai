
package com.example.damai.modules.ticket.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "app.order.relay.enabled=false",
        "app.order.consumer.enabled=false",
        "app.order.recovery.enabled=false",
        "app.rabbitmq.init-on-startup=false"
})
class TicketStockCompensationLuaIntegrationTest {

    private static final Long CATEGORY_ID = 90001L;

    private static final int QUANTITY = 2;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TicketStockScriptExecutor scriptExecutor;

    private String stockKey;

    private String requestKey;

    @BeforeEach
    void setUp() {

        // 为每项测试创建独立Redis Key
        String prefix = "fang-damai:test:compensation:"
                + UUID.randomUUID();

        stockKey = prefix + ":stock";
        requestKey = prefix + ":request";

        /*
         * 模拟已经完成Redis预扣的库存：
         *
         * 原库存：10
         * 用户预扣：2
         * 当前库存：8
         */
        redisTemplate.opsForValue().set(stockKey, "8");

        // 模拟异步预扣请求记录
        redisTemplate.opsForHash().putAll(
                requestKey,
                Map.of(
                        "mode", "ASYNC",
                        "status", "RESERVED",
                        "ticketCategoryId",
                        CATEGORY_ID.toString(),
                        "quantity",
                        Integer.toString(QUANTITY),
                        "outboxId", "12345-0"
                )
        );
    }

    @AfterEach
    void cleanup() {

        // 只清理本次测试创建的Key
        redisTemplate.delete(
                List.of(stockKey, requestKey)
        );
    }

    /**
     * 测试1：
     * 同一次异步预扣请求重复执行补偿，
     * Redis库存只能加回一次。
     */
    @Test
    void shouldCompensateOnlyOnce() {

        // 第一次补偿
        scriptExecutor.compensate(
                stockKey,
                requestKey,
                CATEGORY_ID,
                QUANTITY
        );

        assertEquals(
                "10",
                redisTemplate.opsForValue().get(stockKey)
        );

        assertEquals(
                "COMPENSATED",
                redisTemplate.opsForHash()
                        .get(requestKey, "status")
        );

        // 第二次补偿同一请求
        scriptExecutor.compensate(
                stockKey,
                requestKey,
                CATEGORY_ID,
                QUANTITY
        );

        // 库存不能增加到12
        assertEquals(
                "10",
                redisTemplate.opsForValue().get(stockKey)
        );

        assertEquals(
                "COMPENSATED",
                redisTemplate.opsForHash()
                        .get(requestKey, "status")
        );
    }

    /**
     * 测试2：
     * 已创建订单的请求禁止补偿。
     */
    @Test
    void shouldNotCompensateCreatedOrder() {

        redisTemplate.opsForHash().put(
                requestKey,
                "status",
                "ORDER_CREATED"
        );

        redisTemplate.opsForHash().put(
                requestKey,
                "orderId",
                "10086"
        );

        scriptExecutor.compensate(
                stockKey,
                requestKey,
                CATEGORY_ID,
                QUANTITY
        );

        // 库存必须保持8
        assertEquals(
                "8",
                redisTemplate.opsForValue().get(stockKey)
        );

        // 不能覆盖已创建状态
        assertEquals(
                "ORDER_CREATED",
                redisTemplate.opsForHash()
                        .get(requestKey, "status")
        );
    }

    /**
     * 测试3：
     * 传入数量和原始预扣数量不一致，
     * 禁止补偿。
     */
    @Test
    void shouldRejectDifferentQuantity() {

        // 原请求预扣2张，这里故意传入1张
        scriptExecutor.compensate(
                stockKey,
                requestKey,
                CATEGORY_ID,
                1
        );

        assertEquals(
                "8",
                redisTemplate.opsForValue().get(stockKey)
        );

        assertEquals(
                "RESERVED",
                redisTemplate.opsForHash()
                        .get(requestKey, "status")
        );
    }

    /**
     * 测试4：
     * 请求记录不存在时，禁止直接增加库存。
     */
    @Test
    void shouldNotCompensateMissingRequest() {

        redisTemplate.delete(requestKey);

        scriptExecutor.compensate(
                stockKey,
                requestKey,
                CATEGORY_ID,
                QUANTITY
        );

        assertEquals(
                "8",
                redisTemplate.opsForValue().get(stockKey)
        );

        assertFalse(
                Boolean.TRUE.equals(
                        redisTemplate.hasKey(requestKey)
                )
        );
    }
}
