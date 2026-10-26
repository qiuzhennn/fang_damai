
package com.example.damai.modules.order.service;

import com.example.damai.modules.order.entity.AsyncOrderGuardSnapshot;
import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.order.mapper.AsyncOrderGuardMapper;
import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.ticket.redis.TicketRedisKeys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 异步订单补偿资格申请单元测试。
 *
 * 验证：
 * 1. 演出结束后允许竞争补偿资格
 * 2. 演出尚未开始时拒绝补偿
 * 3. MySQL已有订单时禁止补偿
 * 4. Guard已取得补偿资格时保持幂等
 *
 * 使用Mockito，不连接真实MySQL和Redis。
 */
@ExtendWith(MockitoExtension.class)
class AsyncOrderCompensationClaimServiceTest {

    private static final String OUTBOX_ID = "12345-0";

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("Asia/Shanghai");

    @Mock
    private AsyncOrderGuardMapper guardMapper;

    @Mock
    private TicketOrderService orderService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @InjectMocks
    private AsyncOrderCompensationClaimService claimService;

    /**
     * 构造一条受理时合法的异步订单消息。
     *
     * expired=true：
     * 演出已开始5分钟，但受理时间在演出开始前。
     *
     * expired=false：
     * 演出在未来10天。
     */
    private OrderCreateMessage message(boolean expired) {

        LocalDateTime now =
                LocalDateTime.now(BUSINESS_ZONE);

        LocalDateTime eventStartTime = expired
                ? now.minusMinutes(5)
                : now.plusDays(10);

        LocalDateTime acceptedAt = expired
                ? eventStartTime.minusMinutes(10)
                : now.minusMinutes(1);

        return new OrderCreateMessage(
                10001L,
                UUID.randomUUID().toString(),
                90001L,
                2,
                new BigDecimal("399.00"),
                eventStartTime,
                acceptedAt
        );
    }

    /**
     * 构造真实异步预扣记录的模拟状态。
     */
    private Map<Object, Object> redisState(String status) {

        Map<Object, Object> state = new HashMap<>();

        state.put("mode", "ASYNC");
        state.put("outboxId", OUTBOX_ID);
        state.put("ticketCategoryId", "90001");
        state.put("quantity", "2");
        state.put("status", status);

        return state;
    }

    /**
     * 模拟Redis中存在有效的RESERVED请求。
     */
    private void mockReservedState(
            OrderCreateMessage message) {

        String requestKey = TicketRedisKeys.request(
                message.userId(),
                message.requestId()
        );

        when(redisTemplate.opsForHash())
                .thenReturn(hashOperations);

        when(hashOperations.entries(requestKey))
                .thenReturn(redisState("RESERVED"));
    }

    // ===========================================
    // 测试1：演出已开始，成功取得补偿资格
    // ===========================================


    @Test
    void shouldClaimWhenEventExpired() {

        OrderCreateMessage message = message(true);

        // 1. 模拟Redis已经预扣
        mockReservedState(message);

        // 2. 模拟首次取得Guard补偿资格
        when(guardMapper.claimCompensation(
                eq(message.userId()),
                eq(message.requestId()),
                eq(message.ticketCategoryId()),
                eq(message.quantity()),
                eq(OUTBOX_ID),
                eq(message.unitPrice()),
                eq(message.eventStartTime())
        )).thenReturn(1);

        // 3. 关键：模拟插入后查出的完整Guard快照
        mockGuardSnapshot(message, 2);

        // 4. MySQL订单查询默认返回null，表示没有订单
        CompensationClaimResult result =
                claimService.claimForExpiredEvent(
                        message,
                        OUTBOX_ID
                );

        assertEquals(
                CompensationClaimResult.CLAIMED,
                result
        );

        // 必须查询完整Guard
        verify(guardMapper).findGuard(
                message.userId(),
                message.requestId()
        );

        // 必须核对MySQL订单
        verify(orderService).findByRequestId(
                message.userId(),
                message.requestId()
        );
    }


    // ===========================================
    // 测试2：演出尚未开始，禁止申请补偿
    // ===========================================

    @Test
    void shouldRejectBeforeEventStarted() {

        // 演出在10天后
        OrderCreateMessage message = message(false);

        CompensationClaimResult result =
                claimService.claimForExpiredEvent(
                        message,
                        OUTBOX_ID
                );

        assertEquals(
                CompensationClaimResult.NOT_EXPIRED,
                result
        );

        // 尚未开始，不需要查询Redis或操作MySQL
        verifyNoInteractions(
                redisTemplate,
                guardMapper,
                orderService
        );
    }

    // ===========================================
    // 测试3：MySQL已有订单，禁止取得补偿资格
    // ===========================================


    @Test
    void shouldRejectWhenOrderAlreadyExists() {

        OrderCreateMessage message = message(true);

        // 1. Redis仍然是RESERVED
        mockReservedState(message);

        // 2. Guard插入成功
        when(guardMapper.claimCompensation(
                eq(message.userId()),
                eq(message.requestId()),
                eq(message.ticketCategoryId()),
                eq(message.quantity()),
                eq(OUTBOX_ID),
                eq(message.unitPrice()),
                eq(message.eventStartTime())
        )).thenReturn(1);

        // 3. 模拟合法的Guard快照
        mockGuardSnapshot(message, 2);

        // 4. 模拟MySQL已经存在订单
        TicketOrder existing = new TicketOrder();
        existing.setId(10086L);

        when(orderService.findByRequestId(
                message.userId(),
                message.requestId()
        )).thenReturn(existing);

        // 5. 应该因为订单存在而拒绝补偿
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> claimService.claimForExpiredEvent(
                        message,
                        OUTBOX_ID
                )
        );

        assertTrue(
                exception.getMessage().contains("MySQL已有订单"),
                "实际异常：" + exception.getMessage()
        );

        // 确实执行到订单核对
        verify(orderService).findByRequestId(
                message.userId(),
                message.requestId()
        );
    }

    // ===========================================
    // 测试4：Guard已经获得补偿资格
    // ===========================================


    @Test
    void shouldResumeExistingClaim() {

        OrderCreateMessage message = message(true);

        // 1. Redis原预扣记录仍然存在
        mockReservedState(message);

        // 2. 模拟Guard已经被其他事务取得
        when(guardMapper.claimCompensation(
                eq(message.userId()),
                eq(message.requestId()),
                eq(message.ticketCategoryId()),
                eq(message.quantity()),
                eq(OUTBOX_ID),
                eq(message.unitPrice()),
                eq(message.eventStartTime())
        )).thenReturn(0);

        // 3. 数据库中已经存在status=2的Guard
        mockGuardSnapshot(message, 2);

        // 4. 再次申请补偿资格
        CompensationClaimResult result =
                claimService.claimForExpiredEvent(
                        message,
                        OUTBOX_ID
                );

        // 5. 应识别之前已经获得资格
        assertEquals(
                CompensationClaimResult.ALREADY_CLAIMED,
                result
        );

        // 6. 必须读取已有Guard完整快照
        verify(guardMapper).findGuard(
                message.userId(),
                message.requestId()
        );

        // 本分支无需重新执行订单查询
        verifyNoInteractions(orderService);
    }




    /**
     * 模拟MySQL查询出的完整Guard记录。
     *
     * 注意：
     * 必须使用当前测试传入的同一个message，
     * 不能在这里重新生成OrderCreateMessage。
     */
    private void mockGuardSnapshot(
            OrderCreateMessage message,
            int status) {

        AsyncOrderGuardSnapshot snapshot =
                new AsyncOrderGuardSnapshot(
                        message.userId(),
                        message.requestId(),
                        message.ticketCategoryId(),
                        message.quantity(),
                        OUTBOX_ID,
                        message.unitPrice(),
                        message.eventStartTime(),
                        status,
                        null
                );

        // 先验证构造出来的测试快照本身合法
        assertTrue(
                AsyncOrderGuardVerifier.matches(
                        message,
                        OUTBOX_ID,
                        snapshot
                ),
                "测试构造的Guard快照与消息不一致"
        );

        // 模拟新的Mapper方法
        when(guardMapper.findGuard(
                message.userId(),
                message.requestId()
        )).thenReturn(snapshot);
    }


}
