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

    public OrderBacklogGuard(
            StringRedisTemplate redisTemplate,
            @Value("${app.order.backlog.max-outbox-pending:1000}")
            long maxOutboxPending) {

        if (maxOutboxPending < 1) {
            throw new IllegalArgumentException(
                    "待转发任务积压阈值必须大于0"
            );
        }

        this.redisTemplate = redisTemplate;
        this.maxOutboxPending = maxOutboxPending;

        this.backlogScript = new DefaultRedisScript<>();
        this.backlogScript.setLocation(
                new ClassPathResource(
                        "lua/order_outbox_backlog.lua"
                )
        );
        this.backlogScript.setResultType(Long.class);
    }

    /**
     * 新购买预扣库存之前，检查转发链路是否仍能继续承接任务。
     */
    public void check() {
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