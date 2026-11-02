package com.example.damai.modules.order.ratelimit;

import com.example.damai.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RabbitQueueBacklogCacheTest {

    private final RabbitQueueBacklogReader reader =
            mock(RabbitQueueBacklogReader.class);

    // 手动推进经过的时间，测试时不用真实等待5秒。
    private final AtomicLong nowNanos = new AtomicLong();

    private final RabbitQueueBacklogCache cache =
            new RabbitQueueBacklogCache(
                    reader,
                    1000,
                    5000,
                    nowNanos::get
            );

    @Test
    void unknownStateShouldRejectNewPurchase() {
        // 尚未成功取得队列状态，不能直接认为没有积压。
        assertStateUnavailable();

        // 下单读取快照时，不应触发HTTP查询。
        verifyNoInteractions(reader);
    }

    @Test
    void failedFirstRefreshShouldRemainUnknown() {
        when(reader.readPendingCount())
                .thenThrow(new IllegalStateException("模拟管理接口不可用"));

        cache.refresh();

        // 首次查询失败，仍然没有可用状态，不能默认返回0。
        assertStateUnavailable();
    }

    @Test
    void successfulRefreshShouldProvideZeroBacklog() {
        when(reader.readPendingCount()).thenReturn(0L);

        cache.refresh();

        // 已确认的零积压是有效状态，应允许后续继续判断受理条件。
        assertEquals(0L, cache.readPendingCount());
        assertEquals(0L, cache.readPendingCount());

        // 多次读取使用同一份快照，只在刷新时查询了一次。
        verify(reader, times(1)).readPendingCount();
    }

    @Test
    void snapshotShouldExpireAtConfiguredAge() {
        when(reader.readPendingCount()).thenReturn(2L);

        cache.refresh();

        advanceMillis(4999);

        // 距离成功查询尚未达到5秒，状态仍然有效。
        assertEquals(2L, cache.readPendingCount());

        advanceMillis(1);

        // 恰好达到有效期，旧状态不能再作为受理依据。
        assertStateUnavailable();
    }

    @Test
    void failedRefreshShouldNotExtendLifetimeAndSuccessShouldRecover() {
        when(reader.readPendingCount())
                .thenReturn(2L)
                .thenThrow(new IllegalStateException("模拟查询失败"))
                .thenReturn(1L);

        // 第一次成功查询发生在第0秒。
        cache.refresh();

        advanceMillis(4000);
        cache.refresh();

        // 第4秒刷新失败，仍可使用尚未过期的成功状态。
        assertEquals(2L, cache.readPendingCount());

        advanceMillis(1000);

        // 有效期仍从第0秒计算，不能因第4秒尝试过查询而延长。
        assertStateUnavailable();

        // 第5秒重新查询成功，新状态应立即可用。
        cache.refresh();

        assertEquals(1L, cache.readPendingCount());
    }

    private void advanceMillis(long milliseconds) {
        nowNanos.addAndGet(
                TimeUnit.MILLISECONDS.toNanos(milliseconds)
        );
    }

    private void assertStateUnavailable() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                cache::readPendingCount
        );

        assertEquals("SYSTEM_BUSY", exception.getCode());
    }
}