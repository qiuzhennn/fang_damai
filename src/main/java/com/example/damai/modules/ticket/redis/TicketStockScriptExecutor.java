package com.example.damai.modules.ticket.redis;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

import com.example.damai.modules.order.mq.OrderCreateMessage;

/**
 * 库存脚本执行组件，只负责调用 Redis。
 * 负责接收 Java 业务层传来的扣库存请求，
 * 准备 Redis Key 和参数，然后调用配置好的 Lua 脚本。
 */
@Component
public class TicketStockScriptExecutor {

    private final StringRedisTemplate redisTemplate;

    private final DefaultRedisScript<Long> reserveScript;
    private final DefaultRedisScript<Long> markCreatedScript;
    private final DefaultRedisScript<Long> compensateScript;
    private final DefaultRedisScript<Long> releaseClosedScript;
    private final DefaultRedisScript<Long> cancelWarmupScript;
    private final DefaultRedisScript<Long> reserveAsyncScript;

    public TicketStockScriptExecutor(
            StringRedisTemplate redisTemplate,

            @Qualifier("ticketStockReserveScript")
            DefaultRedisScript<Long> reserveScript,

            @Qualifier("ticketRequestMarkCreatedScript")
            DefaultRedisScript<Long> markCreatedScript,

            @Qualifier("ticketStockCompensateScript")
            DefaultRedisScript<Long> compensateScript,

            @Qualifier("ticketStockReleaseClosedScript")
            DefaultRedisScript<Long> releaseClosedScript,

            @Qualifier("ticketStockCancelWarmupScript")
            DefaultRedisScript<Long> cancelWarmupScript,
            @Qualifier("ticketStockReserveAsyncScript")
            DefaultRedisScript<Long> reserveAsyncScript) {

        this.redisTemplate = redisTemplate;
        this.reserveScript = reserveScript;
        this.markCreatedScript = markCreatedScript;
        this.compensateScript = compensateScript;
        this.releaseClosedScript = releaseClosedScript;
        this.cancelWarmupScript = cancelWarmupScript;
        this.reserveAsyncScript = reserveAsyncScript;
    }


    /**
     * 1.检查同一次购买是否已经有记录。有记录就返回原处理状态，不重复预扣。
     * 2.若是新购买则检查库存是否存在、数量是否足够。
     * 3.库存足够时，扣减数量，并记录这次购买的参数和 RESERVED 状态。
     * 判重、扣库存、记录请求在同一个 Lua 脚本中执行，避免两个相同请求都取得占票资格。
     */
    public Long reserve(
            String stockKey,
            String requestKey,
            Long ticketCategoryId,
            int quantity) {

        return redisTemplate.execute(
                reserveScript,
                List.of(stockKey, requestKey),
                Integer.toString(quantity),
                ticketCategoryId.toString()
        );
    }

    /**
     * 将请求记录标记为订单已创建。
     * 这里只执行脚本；调用方必须确认数据库事务已经提交成功。
     */
    public Long markOrderCreated(
            String requestKey,
            Long ticketCategoryId,
            int quantity,
            Long orderId) {

        return redisTemplate.execute(
                markCreatedScript,
                List.of(requestKey),
                ticketCategoryId.toString(),
                Integer.toString(quantity),
                orderId.toString()
        );
    }

    /**
     * 回补一次请求预扣的库存。
     * 调用前必须确认不存在成功订单，也没有仍在执行的创建任务。
     */
    public Long compensate(
            String stockKey,
            String requestKey,
            Long ticketCategoryId,
            int quantity) {

        return redisTemplate.execute(
                compensateScript,
                List.of(stockKey, requestKey),
                ticketCategoryId.toString(),
                Integer.toString(quantity)
        );
    }

    /**
     * 退回已取消、已超时订单占用的Redis库存。
     *
     * 调用前，MySQL中的订单关闭、库存释放和释放任务
     * 必须已经共同提交成功。
     *
     * 脚本根据原购买记录判断是否已释放，
     * 避免任务重试时重复增加库存。
     */
    public Long releaseClosedOrder(
            String stockKey,
            String requestKey,
            Long ticketCategoryId,
            int quantity,
            Long orderId) {

        return redisTemplate.execute(
                releaseClosedScript,
                List.of(stockKey, requestKey),
                ticketCategoryId.toString(),
                Integer.toString(quantity),
                orderId.toString()
        );
    }
    /**
     * 按MySQL库存快照检查Redis库存，符合条件时撤销预热。
     */
    public Long cancelWarmup(
            String stockKey,
            Integer availableStock) {

        return redisTemplate.execute(
                cancelWarmupScript,
                List.of(stockKey),
                availableStock.toString()
        );
    }

    /**
     * 异步预扣库存并将任务登记到 Redis Stream。
     *
     * 返回 0：Redis 预扣和任务登记成功。
     * 不代表 RabbitMQ 已收到消息，也不代表订单创建成功。
     */
    public Long reserveAsync(
            String stockKey,
            String requestKey,
            String streamKey,
            OrderCreateMessage message) {

        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(message.userId(), "userId");
        Objects.requireNonNull(message.requestId(), "requestId");
        Objects.requireNonNull(message.ticketCategoryId(), "ticketCategoryId");
        Objects.requireNonNull(message.quantity(), "quantity");
        Objects.requireNonNull(message.unitPrice(), "unitPrice");
        Objects.requireNonNull(message.eventStartTime(), "eventStartTime");
        Objects.requireNonNull(message.acceptedAt(), "acceptedAt");

        return redisTemplate.execute(
                reserveAsyncScript,
                List.of(stockKey, requestKey, streamKey),
                message.quantity().toString(),
                message.ticketCategoryId().toString(),
                message.userId().toString(),
                message.requestId(),
                message.unitPrice().toPlainString(),
                message.eventStartTime().toString(),
                message.acceptedAt().toString()
        );
    }




// TODO: 在数据库创建事务提交后调用，而不是在事务内部提前调用。
// TODO: 回写失败时以数据库订单为依据恢复请求记录，不能回补库存。
// TODO: 不得把回写失败直接当作订单创建失败，避免用户重复购买。

}