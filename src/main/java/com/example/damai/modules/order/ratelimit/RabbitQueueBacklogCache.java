package com.example.damai.modules.order.ratelimit;

import com.example.damai.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 保存最近一次成功取得的建单队列状态。
 *
 * 下单请求读取本地快照，队列状态由独立任务刷新。
 * 状态未知或过期时，暂停新购买，等待队列状态恢复。
 */
@Component
public class RabbitQueueBacklogCache {

    private static final Logger log =
            LoggerFactory.getLogger(RabbitQueueBacklogCache.class);

    private final RabbitQueueBacklogReader reader;
    private final Duration refreshInterval;
    private final long maxAgeNanos;
    private final LongSupplier nanoTime;

    private final ThreadPoolTaskScheduler scheduler =
            new ThreadPoolTaskScheduler();

    // 一次替换整份成功状态，让积压数量和查询时间保持一致。
    private volatile Snapshot snapshot;

    // 仅由刷新线程使用，同一次连续故障只记录一次告警。
    private boolean refreshFailed;

    @Autowired
    public RabbitQueueBacklogCache(
            RabbitQueueBacklogReader reader,
            @Value("${app.order.backlog.rabbitmq-refresh-ms:1000}")
            long refreshMs,
            @Value("${app.order.backlog.rabbitmq-max-age-ms:5000}")
            long maxAgeMs) {

        this(reader, refreshMs, maxAgeMs, System::nanoTime);
    }

    RabbitQueueBacklogCache(
            RabbitQueueBacklogReader reader,
            long refreshMs,
            long maxAgeMs,
            LongSupplier nanoTime) {

        if (refreshMs < 1 || maxAgeMs <= refreshMs) {
            throw new IllegalArgumentException(
                    "刷新间隔必须大于0，快照有效期必须大于刷新间隔"
            );
        }

        this.reader = reader;
        this.refreshInterval = Duration.ofMillis(refreshMs);
        this.maxAgeNanos = Duration.ofMillis(maxAgeMs).toNanos();
        this.nanoTime = nanoTime;

        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("rabbit-backlog-");
    }

    /**
     * 向受理保护提供最近一次有效的队列积压数量。
     *
     * 未知或过期时停止当前新购买，
     * 避免凭无法确认的队列状态继续预扣库存。
     */
    public long readPendingCount() {
        Snapshot current = snapshot;

        if (current == null
                || nanoTime.getAsLong() - current.updatedAtNanos()
                >= maxAgeNanos) {

            throw new BusinessException(
                    "SYSTEM_BUSY",
                    "暂时无法确认建单队列状态，请稍后重试"
            );
        }

        return current.pendingCount();
    }

    /**
     * 查询成功才更新快照及其有效期。
     *
     * 查询失败时保留最近一次成功状态，
     * 但不能延长旧状态的有效期。
     */
    void refresh() {
        try {
            long pendingCount = reader.readPendingCount();

            if (pendingCount < 0) {
                throw new IllegalStateException(
                        "RabbitMQ建单队列积压数量不能小于0"
                );
            }

            snapshot = new Snapshot(
                    pendingCount,
                    nanoTime.getAsLong()
            );

            if (refreshFailed) {
                log.info("RabbitMQ建单队列状态刷新已恢复");
            }

            refreshFailed = false;

        } catch (RuntimeException exception) {
            if (!refreshFailed) {
                log.warn(
                        "RabbitMQ建单队列状态刷新失败，"
                                + "快照未知或过期时将暂停新购买",
                        exception
                );
            }

            refreshFailed = true;
        }
    }

    @PostConstruct
    public void start() {
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(
                this::refresh,
                refreshInterval
        );
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdown();
    }

    private record Snapshot(
            long pendingCount,
            long updatedAtNanos) {
    }
}