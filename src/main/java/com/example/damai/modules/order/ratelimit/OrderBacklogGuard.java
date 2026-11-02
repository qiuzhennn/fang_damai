package com.example.damai.modules.order.ratelimit;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.ticket.redis.TicketRedisKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 待转发建单任务积压过多时，暂停受理新购买。
 *
 * 已受理的任务继续转发，不丢弃任务、不释放预扣库存。
 * 积压下降后，新购买可以继续竞争库存。
 */
@Component
public class OrderBacklogGuard {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> backlogScript;
    private final long maxOutboxPending;

    private final RabbitQueueBacklogCache rabbitQueueBacklogCache;
    private final long maxRabbitmqPending;

    public OrderBacklogGuard(
            StringRedisTemplate redisTemplate,
            RabbitQueueBacklogCache rabbitQueueBacklogCache,
            @Value("${app.order.backlog.max-outbox-pending:1000}")
            long maxOutboxPending,
            @Value("${app.order.backlog.max-rabbitmq-pending:1000}")
            long maxRabbitmqPending) {

        if (maxOutboxPending < 1 || maxRabbitmqPending < 1) {
            throw new IllegalArgumentException(
                    "积压保护阈值必须大于0"
            );
        }

        this.redisTemplate = redisTemplate;
        this.rabbitQueueBacklogCache = rabbitQueueBacklogCache;
        this.maxOutboxPending = maxOutboxPending;
        this.maxRabbitmqPending = maxRabbitmqPending;

        this.backlogScript = new DefaultRedisScript<>();
        this.backlogScript.setLocation(
                new ClassPathResource("lua/order_outbox_backlog.lua")
        );
        this.backlogScript.setResultType(Long.class);
    }

    /**
     * Redis待转发任务或RabbitMQ待处理消息积压过多时，暂停受理新购买。
     *
     * 已受理的任务继续转发和消费，积压下降后恢复受理。
     */
    public void check() {

        // 已受理的任务可能已经转入RabbitMQ。
        // 即使Redis待转发任务很少，消费者仍可能有大量建单消息尚未处理完成。
        long rabbitmqPendingCount = rabbitQueueBacklogCache.readPendingCount();

        if (rabbitmqPendingCount >= maxRabbitmqPending) {
            // 当前新购买尚未预扣库存，直接提示稍后重试。
            //这里调用的是内存快照,快照未知或过期时,会抛出 SYSTEM_BUSY
            throw new BusinessException(
                    "SYSTEM_BUSY",
                    "建单任务较多，请稍后重试"
            );
        }

        Long pendingCount = redisTemplate.execute(
                backlogScript,
                List.of(TicketRedisKeys.orderCreateOutboxStream()),
                TicketRedisKeys.orderCreateRelayGroup()
        );

        // 无法确认积压量时停止当前调用，
        // 不能按“没有积压”继续接受新的占票任务。
        if (pendingCount == null || pendingCount < 0) {
            throw new IllegalStateException(
                    "无法确认待转发建单任务的积压数量"
            );
        }

        if (pendingCount >= maxOutboxPending) {
            // 当前调用尚未预扣库存，不需要进行库存补偿。
            throw new BusinessException(
                    "SYSTEM_BUSY",
                    "待处理的抢票任务较多，请稍后重试"
            );
        }
    }
}