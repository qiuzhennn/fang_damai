
package com.example.damai.modules.ticket.redis;

import com.example.damai.modules.order.mq.OrderCreateMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "app.rabbitmq.smoke-test=false"
})
class TicketStockReserveAsyncTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TicketStockScriptExecutor executor;

    private String stockKey;
    private String requestKey;
    private String streamKey;

    @BeforeEach
    void prepare() {
        // 每个测试使用不同的 Key，不影响真实票档
        String suffix = UUID.randomUUID().toString();

        stockKey = "fang-damai:test:async:stock:" + suffix;
        requestKey = "fang-damai:test:async:request:" + suffix;
        streamKey = "fang-damai:test:async:outbox:" + suffix;
    }

    @AfterEach
    void cleanup() {
        // 包括 Stream 在内，清理本次测试创建的数据
        redisTemplate.delete(
                List.of(stockKey, requestKey, streamKey)
        );
    }

    private OrderCreateMessage message(int quantity) {
        return new OrderCreateMessage(
                10001L,
                UUID.randomUUID().toString(),
                90001L,
                quantity,
                new BigDecimal("199.00"),
                LocalDateTime.of(2027, 1, 1, 20, 0),
                LocalDateTime.now()
        );
    }

    private Long reserve(OrderCreateMessage message) {
        return executor.reserveAsync(
                stockKey,
                requestKey,
                streamKey,
                message
        );
    }

    /**
     * 测试1：一次预扣，必须同时产生一条任务。
     * 同一个请求重复执行，不允许重复扣库存或生成任务。
     */
    @Test
    void shouldReserveAndCreateOnlyOneTask() {

        redisTemplate.opsForValue().set(
                stockKey, "10", Duration.ofMinutes(5)
        );

        OrderCreateMessage message = message(2);

        // 第一次预扣
        assertEquals(Long.valueOf(0), reserve(message));

        assertEquals("8",
                redisTemplate.opsForValue().get(stockKey));

        assertEquals("RESERVED",
                redisTemplate.opsForHash()
                        .get(requestKey, "status"));

        assertEquals("ASYNC",
                redisTemplate.opsForHash()
                        .get(requestKey, "mode"));

        // Stream 中应恰好有一条消息
        assertEquals(Long.valueOf(1),
                redisTemplate.opsForStream().size(streamKey));

        var records = redisTemplate.opsForStream()
                .range(streamKey, Range.unbounded());

        assertNotNull(records);
        assertEquals(1, records.size());

        // 确认任务对应正确的请求
        assertEquals(message.requestId(),
                records.get(0).getValue().get("requestId"));

        assertEquals("2",
                records.get(0).getValue().get("quantity"));

        // 请求记录保存了 Stream 消息 ID
        assertEquals(records.get(0).getId().getValue(),
                redisTemplate.opsForHash()
                        .get(requestKey, "outboxId"));

        // 第二次提交同一个请求
        assertEquals(Long.valueOf(5), reserve(message));

        // 库存和 Stream 数量均不改变
        assertEquals("8",
                redisTemplate.opsForValue().get(stockKey));

        assertEquals(Long.valueOf(1),
                redisTemplate.opsForStream().size(streamKey));
    }

    /**
     * 测试2：库存不足，不能登记任务。
     */
    @Test
    void shouldNotCreateTaskWhenStockInsufficient() {

        redisTemplate.opsForValue().set(
                stockKey, "1", Duration.ofMinutes(5)
        );

        assertEquals(Long.valueOf(2), reserve(message(2)));

        assertEquals("1",
                redisTemplate.opsForValue().get(stockKey));

        assertEquals(Boolean.FALSE,
                redisTemplate.hasKey(requestKey));

        assertEquals(Boolean.FALSE,
                redisTemplate.hasKey(streamKey));
    }

    /**
     * 测试3：Stream Key 类型错误，
     * 不允许执行库存预扣。
     */
    @Test
    void shouldNotDeductIfStreamInvalid() {

        redisTemplate.opsForValue().set(stockKey, "10");

        // 故意将 Stream Key 写成 String
        redisTemplate.opsForValue().set(
                streamKey, "wrong-type"
        );

        assertEquals(Long.valueOf(10), reserve(message(2)));

        assertEquals("10",
                redisTemplate.opsForValue().get(stockKey));

        assertEquals(Boolean.FALSE,
                redisTemplate.hasKey(requestKey));
    }

    /**
     * 测试4：同步下单请求不能被异步模式接管。
     */
    @Test
    void shouldRejectExistingSyncRequest() {

        redisTemplate.opsForValue().set(stockKey, "10");

        OrderCreateMessage message = message(2);

        // 模拟旧同步下单产生的请求记录
        redisTemplate.opsForHash().putAll(
                requestKey,
                Map.of(
                        "ticketCategoryId", "90001",
                        "quantity", "2",
                        "status", "RESERVED"
                )
        );

        assertEquals(Long.valueOf(11), reserve(message));

        assertEquals("10",
                redisTemplate.opsForValue().get(stockKey));

        assertEquals(Boolean.FALSE,
                redisTemplate.hasKey(streamKey));
    }
}
