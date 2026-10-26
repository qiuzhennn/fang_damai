
package com.example.damai.modules.order.mapper;

import com.example.damai.modules.order.entity.AsyncOrderGuardSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实 MySQL 验证异步订单 Guard 的事务行为。
 *
 * 不使用 Mockito，不修改真实票档库存。
 */
@SpringBootTest(properties = {
        "app.order.relay.enabled=false",
        "app.order.consumer.enabled=false",
        "app.order.recovery.enabled=false",
        "app.rabbitmq.init-on-startup=false"
})
class AsyncOrderGuardIntegrationTest {

    private static final Long TEST_USER_ID = 909090909090L;

    @Autowired
    private AsyncOrderGuardMapper guardMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> testRequestIds = new ArrayList<>();

    /**
     * 创建一个仅用于本次测试的 requestId。
     */
    private String newRequestId() {
        String requestId = UUID.randomUUID().toString();
        testRequestIds.add(requestId);
        return requestId;
    }

    /**
     * 调用真实 MySQL Mapper 申请 Guard。
     */
    private int claim(String requestId) {

        return guardMapper.claimCreate(
                TEST_USER_ID,
                requestId,
                90001L,
                2,
                "12345-0",
                new BigDecimal("399.00"),
                LocalDateTime.of(2027, 1, 1, 20, 0)
        );
    }

    /**
     * 查询测试 Guard 是否存在。
     */
    private int count(String requestId) {

        Integer result = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM tr_order_async_guard
                WHERE user_id = ?
                  AND request_id = ?
                """,
                Integer.class,
                TEST_USER_ID,
                requestId
        );

        return result == null ? 0 : result;
    }

    /**
     * 测试结束后，只删除本次创建的随机请求记录。
     */
    @AfterEach
    void cleanup() {

        for (String requestId : testRequestIds) {

            jdbcTemplate.update(
                    """
                    DELETE FROM tr_order_async_guard
                    WHERE user_id = ?
                      AND request_id = ?
                    """,
                    TEST_USER_ID,
                    requestId
            );
        }
    }

    /**
     * 测试1：
     * Guard INSERT 随 MySQL 事务回滚。
     */
    @Test
    void shouldRollbackGuardWhenTransactionRollsBack() {

        String requestId = newRequestId();

        TransactionTemplate tx =
                new TransactionTemplate(transactionManager);

        // 1. 在事务内插入 Guard，然后主动回滚
        Integer firstResult = tx.execute(status -> {

            int rows = claim(requestId);

            assertEquals(1, rows);

            // 模拟后续建单失败，需要事务回滚
            status.setRollbackOnly();

            return rows;
        });

        assertEquals(1, firstResult);

        // 2. 回滚后，数据库不应该留下 Guard
        assertEquals(0, count(requestId));

        // 3. 重新开启事务，同一请求应能再次申请资格
        Integer secondResult = tx.execute(status ->
                claim(requestId)
        );

        assertEquals(1, secondResult);

        // 4. 提交后应有且只有一条记录
        assertEquals(1, count(requestId));
    }

    /**
     * 测试2：
     * 两个真实 MySQL 事务竞争相同 Guard。
     *
     * 事务A先插入但暂不提交；
     * 事务B尝试插入相同主键。
     *
     * 验证：
     * - B不能绕过A取得资格
     * - A提交后B返回0
     */
    @Test
    void shouldAllowOnlyOneTransactionToClaim()
            throws Exception {

        String requestId = newRequestId();

        TransactionTemplate tx =
                new TransactionTemplate(transactionManager);

        CountDownLatch firstInserted =
                new CountDownLatch(1);

        CountDownLatch releaseFirst =
                new CountDownLatch(1);

        CountDownLatch secondStarted =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            // 线程A：插入Guard，但暂不提交事务
            Future<Integer> first = executor.submit(() ->
                    tx.execute(status -> {

                        int rows = claim(requestId);

                        firstInserted.countDown();

                        try {
                            if (!releaseFirst.await(
                                    10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "等待释放事务A超时"
                                );
                            }
                        } catch (InterruptedException e) {

                            Thread.currentThread().interrupt();

                            throw new IllegalStateException(
                                    "线程A被中断", e
                            );
                        }

                        return rows;
                    })
            );

            // 等待A确实完成INSERT
            assertTrue(
                    firstInserted.await(
                            5, TimeUnit.SECONDS
                    ),
                    "事务A未成功插入Guard"
            );

            // 线程B：尝试申请完全相同的Guard
            Future<Integer> second = executor.submit(() ->
                    tx.execute(status -> {

                        secondStarted.countDown();

                        return claim(requestId);
                    })
            );

            assertTrue(
                    secondStarted.await(
                            5, TimeUnit.SECONDS
                    ),
                    "事务B未启动"
            );

            // A仍未提交，B不应该立即获得处理结果
            assertThrows(
                    TimeoutException.class,
                    () -> second.get(
                            300, TimeUnit.MILLISECONDS
                    ),
                    "事务B不应该绕过未提交的事务A"
            );

            // 现在允许A提交
            releaseFirst.countDown();

            // A成功申请
            assertEquals(
                    1,
                    first.get(10, TimeUnit.SECONDS)
            );

            // B因唯一键冲突没有申请成功
            assertEquals(
                    0,
                    second.get(10, TimeUnit.SECONDS)
            );

            // 数据库最终只有一条Guard记录
            assertEquals(1, count(requestId));

        } finally {

            // 即使断言失败，也必须释放等待的线程
            releaseFirst.countDown();

            executor.shutdown();

            if (!executor.awaitTermination(
                    10, TimeUnit.SECONDS)) {

                executor.shutdownNow();
            }
        }
    }


    @Test
    void shouldBlockCreationAfterCompensationClaim() {

        String requestId = newRequestId();

        TransactionTemplate tx =
                new TransactionTemplate(transactionManager);

        // 补偿事务先申请资格并提交
        Integer compensationResult = tx.execute(status ->
                guardMapper.claimCompensation(
                        TEST_USER_ID,
                        requestId,
                        90001L,
                        2,
                        "12345-0",
                        new BigDecimal("399.00"),
                        LocalDateTime.of(2027, 1, 1, 20, 0)
                )
        );

        assertEquals(1, compensationResult);

        // Guard应当进入补偿处理中
        assertEquals(
                2,
                guardMapper.findStatus(TEST_USER_ID, requestId)
        );

        // 建单事务再申请相同请求
        Integer createResult = tx.execute(status ->
                claim(requestId)
        );

        // 不能再次取得资格
        assertEquals(0, createResult);

        // 数据库中仍然只有一条Guard记录
        assertEquals(1, count(requestId));
    }


    @Test
    void shouldAllowCreationAfterCompensationRollback() {

        String requestId = newRequestId();

        TransactionTemplate tx =
                new TransactionTemplate(transactionManager);

        // 补偿事务申请资格，但是主动回滚
        Integer compensationResult = tx.execute(status -> {

            int rows = guardMapper.claimCompensation(
                    TEST_USER_ID,
                    requestId,
                    90001L,
                    2,
                    "12345-0",
                    new BigDecimal("399.00"),
                    LocalDateTime.of(2027, 1, 1, 20, 0)
            );

            status.setRollbackOnly();

            return rows;
        });

        assertEquals(1, compensationResult);

        // 回滚后不能留下补偿资格
        assertEquals(0, count(requestId));

        // 建单可以正常申请
        Integer createResult = tx.execute(status ->
                claim(requestId)
        );

        assertEquals(1, createResult);
    }


    @Test
    void shouldRejectCompensationAfterOrderCreated() {

        String requestId = newRequestId();

        TransactionTemplate tx =
                new TransactionTemplate(transactionManager);

        // 模拟建单事务申请Guard并完成状态更新
        Integer createResult = tx.execute(status -> {

            int claimed = claim(requestId);

            assertEquals(1, claimed);

            int updated = guardMapper.finishCreate(
                    TEST_USER_ID,
                    requestId,
                    "12345-0",
                    10086L
            );

            assertEquals(1, updated);

            return claimed;
        });

        assertEquals(1, createResult);

        // Guard已经记录订单创建成功
        assertEquals(
                1,
                guardMapper.findStatus(TEST_USER_ID, requestId)
        );

        // 补偿事务再尝试申请资格
        Integer compensationResult = tx.execute(status ->
                guardMapper.claimCompensation(
                        TEST_USER_ID,
                        requestId,
                        90001L,
                        2,
                        "12345-0",
                        new BigDecimal("399.00"),
                        LocalDateTime.of(2027, 1, 1, 20, 0)
                )
        );

        // 必须拒绝取得新的补偿资格
        assertEquals(0, compensationResult);

        // 原来的已创建状态不能改变
        assertEquals(
                1,
                guardMapper.findStatus(TEST_USER_ID, requestId)
        );
    }


    @Test
    void shouldReadCompleteGuardSnapshot() {

        String requestId = newRequestId();

        // 1. 通过真实Mapper插入Guard
        int inserted = claim(requestId);

        assertEquals(1, inserted);

        // 2. 通过新方法查询完整快照
        AsyncOrderGuardSnapshot guard =
                guardMapper.findGuard(TEST_USER_ID, requestId);

        assertNotNull(guard);

        // 3. 核对请求身份
        assertEquals(TEST_USER_ID, guard.userId());
        assertEquals(requestId, guard.requestId());

        // 4. 核对购票信息
        assertEquals(90001L, guard.ticketCategoryId());
        assertEquals(2, guard.quantity());
        assertEquals("12345-0", guard.outboxId());

        // 5. 核对价格快照
        assertEquals(
                0,
                new BigDecimal("399.00")
                        .compareTo(guard.unitPrice())
        );

        // 6. 核对演出时间
        assertEquals(
                LocalDateTime.of(2027, 1, 1, 20, 0),
                guard.eventStartTime()
        );

        // 7. 当前处于建单资格状态
        assertEquals(0, guard.status());

        // 8. 尚未创建订单
        assertNull(guard.orderId());
    }




}
