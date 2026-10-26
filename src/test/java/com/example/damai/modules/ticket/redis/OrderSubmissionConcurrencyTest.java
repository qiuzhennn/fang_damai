package com.example.damai.modules.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.damai.modules.order.dto.OrderCreateRequest;
import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.order.mapper.TicketOrderMapper;
import com.example.damai.modules.ticket.entity.TicketCategory;
import com.example.damai.modules.ticket.mapper.TicketCategoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
/*
* 测试 MySQL 并发重复下单
* */
@SpringBootTest
class OrderSubmissionConcurrencyTest {

    @Autowired
    private OrderSubmissionService submissionService;

    @Autowired
    private TicketOrderService orderService;

    @Autowired
    private TicketOrderMapper orderMapper;

    @Autowired
    private TicketCategoryMapper categoryMapper;

    @Test
    void shouldCreateOnlyOneOrderForConcurrentSameRequest() throws Exception {

        // 替换为专用测试用户和可购票档，不能使用生产数据
        Long userId = 2L;
        Long categoryId = 8L;

        int quantity = 2;
        int requestCount = 20;
        String requestId = UUID.randomUUID().toString();

        TicketCategory before = categoryMapper.selectById(categoryId);
        assertNotNull(before, "测试票档不存在");
        assertTrue(before.getAvailableStock() >= quantity, "测试库存不足");

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待开始信号超时");
                    }

                    // 每个线程使用独立DTO，但请求ID和购买参数相同
                    OrderCreateRequest request = new OrderCreateRequest();
                    request.setTicketCategoryId(categoryId);
                    request.setQuantity(quantity);
                    request.setRequestId(requestId);

                    return submissionService.submit(userId, request);
                }));
            }

            start.countDown();

            Set<Long> orderIds = new HashSet<>();
            for (Future<Long> future : futures) {
                // 任一请求异常或超时，测试直接失败
                Long orderId = future.get(30, TimeUnit.SECONDS);
                assertNotNull(orderId);
                orderIds.add(orderId);
            }

            assertEquals(1, orderIds.size(), "重复请求返回了不同订单");

            List<TicketOrder> orders = findOrders(userId, requestId);
            assertEquals(1, orders.size(), "数据库产生了重复订单");
            assertEquals(orders.get(0).getId(), orderIds.iterator().next());

            TicketCategory after = categoryMapper.selectById(categoryId);
            assertNotNull(after);

            assertEquals(
                    before.getAvailableStock() - quantity,
                    after.getAvailableStock().intValue()
            );
            assertEquals(
                    before.getLockedStock() + quantity,
                    after.getLockedStock().intValue()
            );
            assertEquals(before.getSoldStock(), after.getSoldStock());

            System.out.printf(
                    "请求数：%d，唯一订单数：%d，订单ID：%d%n",
                    requestCount, orders.size(), orders.get(0).getId()
            );
        } finally {
            start.countDown();
            pool.shutdownNow();

            boolean terminated =
                    pool.awaitTermination(10, TimeUnit.SECONDS);

            if (terminated) {
                // 用正常业务取消释放库存，不直接删除订单或手工加库存
                for (TicketOrder order : findOrders(userId, requestId)) {
                    if (Integer.valueOf(0).equals(order.getStatus())) {
                        orderService.cancel(userId, order.getId());
                    }
                }
            }

            // TODO: 若线程未及时结束，确认请求停止后，
            //       按本次requestId查询测试订单并处理，禁止盲目补库存。
            assertTrue(terminated, "测试线程未在规定时间内结束");
        }
    }

    private List<TicketOrder> findOrders(
            Long userId, String requestId) {

        LambdaQueryWrapper<TicketOrder> query =
                new LambdaQueryWrapper<>();

        query.eq(TicketOrder::getUserId, userId)
                .eq(TicketOrder::getRequestId, requestId);

        return orderMapper.selectList(query);
    }
}