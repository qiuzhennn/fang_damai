
package com.example.damai.modules.order.service;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.order.mapper.AsyncOrderGuardMapper;
import com.example.damai.modules.order.mapper.TicketOrderMapper;
import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.ticket.mapper.TicketCategoryMapper;
import com.example.damai.modules.ticket.redis.TicketPurchaseCache;
import com.example.damai.modules.ticket.service.StockReleaseTaskService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 异步订单创建单元测试。
 *
 * 测试目标：
 *
 * 1. 使用受理时的价格快照创建订单
 * 2. 演出开始后禁止创建订单
 * 3. MySQL库存不足时禁止插入订单
 * 4. Guard资格冲突时禁止重复建单
 *
 * 使用Mockito模拟Mapper，不连接真实MySQL。
 */
@ExtendWith(MockitoExtension.class)
class TicketOrderAsyncCreateTest {

    private static final String OUTBOX_ID = "12345-0";

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("Asia/Shanghai");

    // =========================
    // 一、模拟依赖
    // =========================

    @Mock
    private TicketOrderMapper orderMapper;

    @Mock
    private TicketCategoryMapper categoryMapper;

    @Mock
    private TicketPurchaseCache purchaseCache;

    @Mock
    private StockReleaseTaskService releaseTaskService;

    @Mock
    private AsyncOrderGuardMapper asyncOrderGuardMapper;

    // 注入以上Mock依赖
    @InjectMocks
    private TicketOrderService orderService;

    // =========================
    // 二、测试数据构造
    // =========================

    /**
     * 构造合法的异步购票消息。
     *
     * acceptedAt使用当前北京时间，
     * 避免固定日期导致未来测试失效。
     */
    private OrderCreateMessage message(
            BigDecimal price,
            LocalDateTime eventStartTime) {

        LocalDateTime acceptedAt =
                LocalDateTime.now(BUSINESS_ZONE);

        return new OrderCreateMessage(
                10001L,
                UUID.randomUUID().toString(),
                90001L,
                2,
                price,
                eventStartTime,
                acceptedAt
        );
    }

    /**
     * 模拟当前事务成功获取Guard建单资格。
     *
     * 返回1表示Guard记录插入成功。
     */
    private void mockGuardClaimSuccess() {

        when(asyncOrderGuardMapper.claimCreate(
                anyLong(),
                anyString(),
                anyLong(),
                anyInt(),
                anyString(),
                any(BigDecimal.class),
                any(LocalDateTime.class)
        )).thenReturn(1);
    }

    // =========================
    // 三、测试1：正常创建订单
    // =========================

    @Test
    void shouldCreateOrderWithAcceptedPrice() {

        // 1. 演出在10天后
        LocalDateTime eventStartTime =
                LocalDateTime.now(BUSINESS_ZONE)
                        .plusDays(10);

        // 2. 构造受理价格为399元的消息
        OrderCreateMessage message = message(
                new BigDecimal("399.00"),
                eventStartTime
        );

        // 3. 模拟Guard资格获取成功
        mockGuardClaimSuccess();

        // 4. 模拟MySQL库存锁定成功
        when(categoryMapper.lockStock(90001L, 2))
                .thenReturn(1);

        // 5. 模拟MySQL订单插入成功
        when(orderMapper.insert(any(TicketOrder.class)))
                .thenAnswer(invocation -> {

                    TicketOrder order =
                            invocation.getArgument(0);

                    // 模拟数据库自增订单ID
                    order.setId(10086L);

                    return 1;
                });

        // 6. 模拟Guard状态更新成功
        when(asyncOrderGuardMapper.finishCreate(
                anyLong(),
                anyString(),
                anyString(),
                anyLong()
        )).thenReturn(1);

        // 7. 执行异步建单
        Long orderId =
                orderService.createFromReservation(
                        message,
                        OUTBOX_ID
                );

        // 8. 验证返回的订单ID
        assertEquals(10086L, orderId);

        // 9. 捕获真正准备插入MySQL的订单
        ArgumentCaptor<TicketOrder> captor =
                ArgumentCaptor.forClass(TicketOrder.class);

        verify(orderMapper).insert(captor.capture());

        TicketOrder saved = captor.getValue();

        // 10. 验证订单基本信息
        assertEquals(10001L, saved.getUserId());

        assertEquals(
                message.requestId(),
                saved.getRequestId()
        );

        assertEquals(
                90001L,
                saved.getTicketCategoryId()
        );

        assertEquals(2, saved.getQuantity());

        // 11. 验证使用的是受理时价格快照
        assertEquals(
                0,
                saved.getUnitPrice().compareTo(
                        new BigDecimal("399.00")
                )
        );

        // 12. 验证总价：399 × 2 = 798
        assertEquals(
                0,
                saved.getTotalAmount().compareTo(
                        new BigDecimal("798.00")
                )
        );

        // 13. 验证待支付状态
        assertEquals(0, saved.getStatus());

        // 14. 支付截止时间不得超过演出开始时间
        assertNotNull(saved.getExpireTime());

        assertFalse(
                saved.getExpireTime().isAfter(eventStartTime)
        );

        // 15. 异步建单不重新查询当前购买资料
        verifyNoInteractions(purchaseCache);

        // 16. 验证Guard申请成功的方法被调用
        verify(asyncOrderGuardMapper).claimCreate(
                eq(message.userId()),
                eq(message.requestId()),
                eq(message.ticketCategoryId()),
                eq(message.quantity()),
                eq(OUTBOX_ID),
                eq(message.unitPrice()),
                eq(message.eventStartTime())
        );

        // 17. 验证Guard记录了成功创建的订单ID
        verify(asyncOrderGuardMapper).finishCreate(
                eq(message.userId()),
                eq(message.requestId()),
                eq(OUTBOX_ID),
                eq(10086L)
        );

        // 18. 验证库存只被锁定一次
        verify(categoryMapper, times(1))
                .lockStock(90001L, 2);
    }

    // =========================
    // 四、测试2：演出已经开始
    // =========================

    @Test
    void shouldRejectOrderAfterEventStarted() {

        // 1. 演出开始时间为1分钟前
        LocalDateTime eventStartTime =
                LocalDateTime.now(BUSINESS_ZONE)
                        .minusMinutes(1);

        // 2. 受理时间为演出开始前10分钟
        //
        // 这样可以保证消息快照本身合法，
        // 但消费者执行时演出已经开始。
        OrderCreateMessage oldMessage =
                new OrderCreateMessage(
                        10001L,
                        UUID.randomUUID().toString(),
                        90001L,
                        2,
                        new BigDecimal("399.00"),
                        eventStartTime,
                        eventStartTime.minusMinutes(10)
                );

        // 3. 执行建单
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> orderService.createFromReservation(
                        oldMessage,
                        OUTBOX_ID
                )
        );

        // 4. 确认确实是演出开始引起的拒绝
        assertTrue(
                exception.getMessage().contains("演出"),
                "实际异常：" + exception.getMessage()
        );

        // 5. 演出已经开始，不应申请Guard或锁库存
        verifyNoInteractions(
                asyncOrderGuardMapper,
                categoryMapper,
                orderMapper
        );
    }

    // =========================
    // 五、测试3：MySQL库存不足
    // =========================

    @Test
    void shouldRejectWhenMysqlStockInsufficient() {

        // 1. 构造合法消息
        LocalDateTime eventStartTime =
                LocalDateTime.now(BUSINESS_ZONE)
                        .plusDays(10);

        OrderCreateMessage message = message(
                new BigDecimal("399.00"),
                eventStartTime
        );

        // 2. Guard资格申请成功
        mockGuardClaimSuccess();

        // 3. 模拟MySQL库存不足
        //
        // categoryMapper是Mockito Mock。
        // lockStock返回int，默认值为0。
        //
        // 因此不需要编写：
        // when(categoryMapper.lockStock(...))
        //         .thenReturn(0);

        // 4. 执行建单，预期库存不足异常
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> orderService.createFromReservation(
                        message,
                        OUTBOX_ID
                )
        );

        // 5. 确认不是参数校验失败
        assertTrue(
                exception.getMessage().contains("库存不足"),
                "实际异常：" + exception.getMessage()
        );

        // 6. Guard资格申请已经执行
        verify(asyncOrderGuardMapper, times(1))
                .claimCreate(
                        anyLong(),
                        anyString(),
                        anyLong(),
                        anyInt(),
                        anyString(),
                        any(BigDecimal.class),
                        any(LocalDateTime.class)
                );

        // 7. MySQL库存锁定确实执行了一次
        verify(categoryMapper, times(1))
                .lockStock(90001L, 2);

        // 8. 库存不足时，禁止插入订单
        verify(orderMapper, never())
                .insert(any(TicketOrder.class));

        // 9. 没有创建订单，不能将Guard更新为成功
        verify(asyncOrderGuardMapper, never())
                .finishCreate(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyLong()
                );
    }

    // =========================
    // 六、测试4：Guard资格冲突
    // =========================

    @Test
    void shouldRejectWhenGuardAlreadyClaimed() {

        // 1. 构造合法消息
        LocalDateTime eventStartTime =
                LocalDateTime.now(BUSINESS_ZONE)
                        .plusDays(10);

        OrderCreateMessage message = message(
                new BigDecimal("399.00"),
                eventStartTime
        );

        // 2. 模拟相同请求已经存在Guard记录
        //
        // claimCreate返回0，
        // 表示当前事务没有获取到建单资格。
        when(asyncOrderGuardMapper.claimCreate(
                anyLong(),
                anyString(),
                anyLong(),
                anyInt(),
                anyString(),
                any(BigDecimal.class),
                any(LocalDateTime.class)
        )).thenReturn(0);

        // 3. 执行建单
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> orderService.createFromReservation(
                        message,
                        OUTBOX_ID
                )
        );

        // 4. 必须是Guard资格冲突异常
        assertTrue(
                exception.getMessage().contains("Guard资格冲突"),
                "实际异常：" + exception.getMessage()
        );

        // 5. 验证申请资格的方法执行了一次
        verify(asyncOrderGuardMapper, times(1))
                .claimCreate(
                        eq(message.userId()),
                        eq(message.requestId()),
                        eq(message.ticketCategoryId()),
                        eq(message.quantity()),
                        eq(OUTBOX_ID),
                        eq(message.unitPrice()),
                        eq(message.eventStartTime())
                );

        // 6. 没有取得Guard资格，禁止操作库存和订单
        verifyNoInteractions(
                categoryMapper,
                orderMapper
        );

        // 7. 不允许将Guard标记为创建成功
        verify(asyncOrderGuardMapper, never())
                .finishCreate(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyLong()
                );
    }
}
