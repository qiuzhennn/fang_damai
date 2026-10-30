package com.example.damai.modules.order.service;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.order.ratelimit.OrderBacklogGuard;
import com.example.damai.modules.ticket.redis.TicketRedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderBacklogGuardTest {

    private final StringRedisTemplate redisTemplate =
            mock(StringRedisTemplate.class);

    // 使用较小阈值，验证边界：达到3个待转发任务就拒绝。
    private final OrderBacklogGuard guard =
            new OrderBacklogGuard(redisTemplate, 3);

    @Test
    void noBacklogShouldAllowPurchase() {
        mockBacklog(0L);

        // 转发链路没有积压，允许新购买继续处理。
        assertDoesNotThrow(guard::check);
    }

    @Test
    void shouldRejectAtThresholdAndRecoverAfterBacklogDrops() {
        mockBacklog(3L);

        // 达到阈值，暂停接受新的占票任务。
        BusinessException exception = assertThrows(
                BusinessException.class,
                guard::check
        );

        assertEquals("SYSTEM_BUSY", exception.getCode());

        // 模拟Relay继续转发，待转发任务从3个下降到2个。
        mockBacklog(2L);

        // 每次检查重新读取积压量，下降后应恢复放行。
        assertDoesNotThrow(guard::check);
    }

    @Test
    void unknownBacklogShouldStopPurchase() {
        mockBacklog(-1L);

        // 组未就绪或积压量未知，不能当作没有积压继续占票。
        assertThrows(
                IllegalStateException.class,
                guard::check
        );
    }

    @Test
    void missingScriptResultShouldStopPurchase() {
        mockBacklog(null);

        // 没有拿到脚本结果，也不能默认放行。
        assertThrows(
                IllegalStateException.class,
                guard::check
        );
    }

    private void mockBacklog(Long count) {
        doReturn(count).when(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        TicketRedisKeys.orderCreateOutboxStream()
                )),
                eq(TicketRedisKeys.orderCreateRelayGroup())
        );
    }
}