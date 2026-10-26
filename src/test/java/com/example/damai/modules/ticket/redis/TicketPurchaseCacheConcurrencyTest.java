package com.example.damai.modules.ticket.redis;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.program.mapper.EventMapper;
import com.example.damai.modules.program.mapper.EventSessionMapper;
import com.example.damai.modules.ticket.mapper.TicketCategoryMapper;
import com.example.damai.modules.ticket.redis.TicketPurchaseCache.PurchaseInfo;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class TicketPurchaseCacheConcurrencyTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TicketPurchaseCache purchaseCache;

    /*
     * Spy会保留真实Mapper的行为，同时记录方法调用。
     * 因此下面仍会真正查询数据库。
     */
    @MockitoSpyBean
    private TicketCategoryMapper categoryMapper;

    @MockitoSpyBean
    private EventSessionMapper sessionMapper;

    @MockitoSpyBean
    private EventMapper eventMapper;

    @Test
    void shouldLoadDatabaseOnceForConcurrentRequests() throws Exception {

        Long categoryId = 8L;
        int requestCount = 20;

        /*
         * 只在测试中增加100毫秒延迟，
         * 放大缓存尚未生成时的竞争窗口。
         */
        // 先保存Spy原本的调用行为，保留对真实MyBatis代理的委托
        Answer<?> originalAnswer = mockingDetails(categoryMapper)
                .getMockCreationSettings()
                .getDefaultAnswer();

// 在原有调用行为前增加延迟，放大并发竞争窗口
        doAnswer(invocation -> {
            Thread.sleep(100);

            // 沿用原调用行为，让MyBatis代理执行查询
            return originalAnswer.answer(invocation);
        }).when(categoryMapper).selectById(categoryId);
        // 提前初始化连接池，避免把首次建立连接的耗时算进并发加载
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.isValid(3), "数据库连接不可用");
        }
        // 切换版本，让本次测试从没有缓存的状态开始
        purchaseCache.invalidateAfterCommit();

        // 清除启动阶段的调用记录，保留上面的延迟设置
        clearInvocations(categoryMapper, sessionMapper, eventMapper);

        // 20个线程，让20次调用能够同时参与竞争
        ExecutorService pool =
                Executors.newFixedThreadPool(requestCount);

        // ready：确认20个线程都已经准备好
        CountDownLatch ready = new CountDownLatch(requestCount);

        // start：统一放行，让它们尽量同时开始
        CountDownLatch start = new CountDownLatch(1);

        List<Future<PurchaseInfo>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待开始信号超时");
                    }

                    return purchaseCache.get(categoryId);
                }));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "并发线程未全部准备完成"
            );

            // 所有线程一起开始读取同一个票档
            start.countDown();

            List<PurchaseInfo> successes = new ArrayList<>();
            int busyCount = 0;

            for (Future<PurchaseInfo> future : futures) {
                try {
                    successes.add(future.get(15, TimeUnit.SECONDS));

                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();

                    /*
                     * 当前业务允许：
                     * 等待超过预算后返回“资料加载中”。
                     * 记录这种结果，其他异常直接让测试失败。
                     */
                    if (cause instanceof BusinessException business
                            && "PURCHASE_INFO_LOADING"
                            .equals(business.getCode())) {
                        busyCount++;
                    } else {
                        throw new AssertionError(
                                "并发读取出现非预期异常", cause);
                    }
                }
            }

            assertFalse(successes.isEmpty(), "至少应该有一次加载成功");

            assertEquals(
                    requestCount,
                    successes.size() + busyCount
            );

            PurchaseInfo expected = successes.get(0);

            // 成功的调用应获得相同的购买资料
            assertEquals(categoryId, expected.ticketCategoryId());

            for (PurchaseInfo result : successes) {
                assertEquals(expected, result);
            }

            // 再读一次，确认生成的缓存可以继续使用
            assertEquals(expected, purchaseCache.get(categoryId));

            /*
             * 核心验证：
             * 20次并发调用，加上后面的一次缓存读取，
             * 三张资料表仍各查询一次。
             */
            verify(categoryMapper, times(1))
                    .selectById(categoryId);

            verify(sessionMapper, times(1))
                    .selectById(expected.sessionId());

            verify(eventMapper, times(1))
                    .selectById(expected.eventId());

            System.out.printf(
                    "并发调用：%d，成功：%d，繁忙：%d%n",
                    requestCount, successes.size(), busyCount
            );

            System.out.println(
                    "验证通过：票档、场次、演出各查询1次"
            );

        } finally {
            // 即使测试失败，也释放仍在等待开始信号的线程
            start.countDown();

            pool.shutdownNow();

            assertTrue(
                    pool.awaitTermination(10, TimeUnit.SECONDS),
                    "测试线程未正常结束"
            );
        }
    }
}