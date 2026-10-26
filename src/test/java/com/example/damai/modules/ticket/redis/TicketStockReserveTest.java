package com.example.damai.modules.ticket.redis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TicketStockReserveTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TicketStockScriptExecutor executor;


    /*测试同一请求重复发送，只扣一次库存，以及相同请求 ID 更换参数时会被拒绝。*/
    @Test
    void shouldNotDeductAgainForSameRequest() {

        // 独立测试Key，不操作实际票档库存
        String suffix = UUID.randomUUID().toString();
        String stockKey = "fang-damai:test:reserve:stock:" + suffix;
        String requestKey = "fang-damai:test:reserve:request:" + suffix;
        String anotherRequestKey = requestKey + ":another";

        try {
            redisTemplate.opsForValue().set(stockKey, "10", Duration.ofMinutes(5));

            // 第一次购买两张：预扣成功，剩余8张
            assertEquals(Long.valueOf(0), executor.reserve(stockKey, requestKey, 10L, 2));
            assertEquals("8", redisTemplate.opsForValue().get(stockKey));

            // 请求记录保存了本次购买参数和状态
            assertEquals("10", redisTemplate.opsForHash().get(requestKey, "ticketCategoryId"));
            assertEquals("2", redisTemplate.opsForHash().get(requestKey, "quantity"));
            assertEquals("RESERVED", redisTemplate.opsForHash().get(requestKey, "status"));

            // 相同请求、相同参数：返回已预扣，库存仍为8
            assertEquals(Long.valueOf(5), executor.reserve(stockKey, requestKey, 10L, 2));
            assertEquals("8", redisTemplate.opsForValue().get(stockKey));

            // 相同请求改成买3张：参数冲突，不扣库存
            assertEquals(Long.valueOf(6), executor.reserve(stockKey, requestKey, 10L, 3));
            assertEquals("8", redisTemplate.opsForValue().get(stockKey));

            // 相同请求改票档：同样拒绝
            assertEquals(Long.valueOf(6), executor.reserve(stockKey, requestKey, 11L, 2));
            assertEquals("8", redisTemplate.opsForValue().get(stockKey));

            // 新的请求记录Key：视为另一笔购买，可以再次预扣
            assertEquals(Long.valueOf(0), executor.reserve(stockKey, anotherRequestKey, 10L, 2));
            assertEquals("6", redisTemplate.opsForValue().get(stockKey));
        } finally {
            // Lua创建的请求记录没有TTL，测试必须显式清理
            redisTemplate.delete(requestKey);
            redisTemplate.delete(anotherRequestKey);
            redisTemplate.delete(stockKey);
        }
    }

    /*验证多个线程同时提交同一个请求，也只扣一次库存。
    *
    * 同一个请求并发到达 50 次，Redis 只预扣一次。
    * */
    @Test
    void shouldReserveOnlyOnceForConcurrentDuplicateRequests()
            throws Exception {

        String suffix = UUID.randomUUID().toString();
        String stockKey = "fang-damai:test:reserve:stock:" + suffix;
        String requestKey = "fang-damai:test:reserve:request:" + suffix;

        int requestCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();

        try {
            redisTemplate.opsForValue().set(
                    stockKey, "10", Duration.ofMinutes(5));

            for (int i = 0; i < requestCount; i++) {
                futures.add(pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待开始信号超时");
                    }

                    // 全部使用同一个请求Key，模拟同一次购买重复到达
                    return executor.reserve(
                            stockKey, requestKey, 10L, 2);
                }));
            }

            start.countDown();

            int firstSuccess = 0;
            int alreadyReserved = 0;

            for (Future<Long> future : futures) {
                Long result = future.get(30, TimeUnit.SECONDS);

                if (Long.valueOf(0).equals(result)) {
                    firstSuccess++;
                } else if (Long.valueOf(5).equals(result)) {
                    alreadyReserved++;
                } else {
                    throw new AssertionError(
                            "非预期返回码：" + result);
                }
            }

            // 只有一次首次预扣，其余请求都识别为重复
            assertEquals(1, firstSuccess);
            assertEquals(requestCount - 1, alreadyReserved);

            // 买两张只扣一次：10 → 8
            assertEquals("8", redisTemplate.opsForValue().get(stockKey));

            assertEquals(
                    "RESERVED",
                    redisTemplate.opsForHash().get(requestKey, "status")
            );

            System.out.printf(
                    "首次预扣：%d，重复请求：%d，剩余库存：8%n",
                    firstSuccess, alreadyReserved
            );
        } finally {
            start.countDown();
            pool.shutdownNow();

            boolean terminated =
                    pool.awaitTermination(10, TimeUnit.SECONDS);

            if (terminated) {
                redisTemplate.delete(requestKey);
                redisTemplate.delete(stockKey);
            }

            // TODO: 请求记录当前无TTL；若测试异常且线程未停止，
            //       确认任务停止后手动清理本次UUID对应的测试Key。
            assertTrue(terminated, "测试线程未在规定时间内结束");
        }
    }

    @Test
    void shouldMarkOrderCreatedWithoutDeductingAgain() {
        String suffix = UUID.randomUUID().toString();
        String stockKey = "fang-damai:test:reserve:stock:" + suffix;
        String requestKey = "fang-damai:test:reserve:request:" + suffix;

        try {
            redisTemplate.opsForValue().set(
                    stockKey, "10", Duration.ofMinutes(5));

            // 1. 预扣两张，库存变成8，请求状态为RESERVED
            assertEquals(
                    Long.valueOf(0),
                    executor.reserve(stockKey, requestKey, 10L, 2)
            );
            assertEquals("8", redisTemplate.opsForValue().get(stockKey));

            // 2. 假设数据库已创建订单15，并提交成功
            // 这里只测试Redis回写，不实际创建数据库订单
            assertEquals(
                    Long.valueOf(0),
                    executor.markOrderCreated(requestKey, 10L, 2, 15L)
            );

            assertEquals(
                    "ORDER_CREATED",
                    redisTemplate.opsForHash().get(requestKey, "status")
            );
            assertEquals(
                    "15",
                    redisTemplate.opsForHash().get(requestKey, "orderId")
            );

            // 回写成功状态不应再次扣库存
            assertEquals("8", redisTemplate.opsForValue().get(stockKey));

            // 3. 同一订单重复回写，应成功且库存不变
            assertEquals(
                    Long.valueOf(0),
                    executor.markOrderCreated(requestKey, 10L, 2, 15L)
            );
            assertEquals("8", redisTemplate.opsForValue().get(stockKey));

            // 4. 同一请求不能被改为另一个订单
            assertEquals(
                    Long.valueOf(2),
                    executor.markOrderCreated(requestKey, 10L, 2, 16L)
            );
            assertEquals(
                    "15",
                    redisTemplate.opsForHash().get(requestKey, "orderId")
            );

            // 5. 原购买请求再次预扣，应返回“订单已创建”
            assertEquals(
                    Long.valueOf(7),
                    executor.reserve(stockKey, requestKey, 10L, 2)
            );
            assertEquals("8", redisTemplate.opsForValue().get(stockKey));
        } finally {
            redisTemplate.delete(requestKey);
            redisTemplate.delete(stockKey);
        }
    }
}