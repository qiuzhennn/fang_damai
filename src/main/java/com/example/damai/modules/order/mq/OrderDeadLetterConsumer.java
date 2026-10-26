package com.example.damai.modules.order.mq;

import com.example.damai.common.config.RabbitMqConfig;
import com.example.damai.modules.order.service.OrderDeadLetterRecoveryService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@ConditionalOnProperty(
        name = "app.order.recovery.enabled",
        havingValue = "true"
)
public class OrderDeadLetterConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(OrderDeadLetterConsumer.class);

    private final OrderDeadLetterRecoveryService recoveryService;
    private final OrderRecoveryParkingPublisher parkingPublisher;

    private final JacksonJsonMessageConverter converter =
            new JacksonJsonMessageConverter(
                    "com.example.damai.modules.order.mq"
            );

    public OrderDeadLetterConsumer(
            OrderDeadLetterRecoveryService recoveryService,
            OrderRecoveryParkingPublisher parkingPublisher) {

        this.recoveryService = recoveryService;
        this.parkingPublisher = parkingPublisher;
    }

    @RabbitListener(
            id = "damaiOrderRecoveryListener",
            queues = RabbitMqConfig.ORDER_CREATE_DLQ,
            containerFactory = "orderRecoveryListenerFactory",
            ackMode = "MANUAL",
            concurrency = "1"
    )
    public void onMessage(
            Message raw,
            Channel channel) throws IOException {

        long tag =
                raw.getMessageProperties().getDeliveryTag();

        String messageId =
                raw.getMessageProperties().getMessageId();

        String reason;

        try {
            // 1. 取得原始 Stream 任务标识
            Object header = raw.getMessageProperties()
                    .getHeaders().get("outboxId");

            if (!(header instanceof String outboxId)
                    || outboxId.isBlank()) {
                throw new IllegalArgumentException(
                        "死信消息缺少outboxId"
                );
            }

            // 2. 解析消息
            Object decoded = converter.fromMessage(raw);

            if (!(decoded instanceof OrderCreateMessage message)) {
                throw new IllegalArgumentException(
                        "死信消息类型不正确"
                );
            }

            if (!message.requestId().equals(messageId)) {
                throw new IllegalArgumentException(
                        "requestId与MessageId不一致"
                );
            }

            // 3. 对账 MySQL 与 Redis
            OrderRecoveryDecision decision =
                    recoveryService.reconcile(
                            message,
                            outboxId
                    );

            // 4. 已有确定结果，可以直接ACK
            if (decision == OrderRecoveryDecision.REDIS_REPAIRED
                    || decision == OrderRecoveryDecision.ALREADY_CREATED) {

                log.info(
                        "异常订单恢复完成，requestId={}, decision={}",
                        message.requestId(),
                        decision
                );

                channel.basicAck(tag, false);
                return;
            }

            reason = decision.name();

        } catch (RuntimeException e) {

            // 对账异常或消息不可解析，不直接删除消息
            log.error(
                    "订单死信对账失败，messageId={}，准备隔离",
                    messageId,
                    e
            );

            reason = "RECONCILIATION_EXCEPTION";
        }

        // 5. 未能确认处理完成，转存至 Parking Queue
        try {
            parkingPublisher.park(raw, reason);

        }  catch (RuntimeException e) {

        log.error(
                "Parking Queue发布失败，停止确认当前DLQ消息，" +
                        "需要检查RabbitMQ并恢复消费者，messageId={}",
                messageId,
                e
        );

        // 不 ACK，也不立即 NACK 重新入队。
        // 当前消息保持 Unacked。
        // 配合专用消费者 prefetch=1，避免继续领取新消息。
        return;
    }

        // 6. 已确认转存，才能删除DLQ里的原消息
        channel.basicAck(tag, false);

        log.warn(
                "订单消息已转入Parking Queue，messageId={}, reason={}",
                messageId,
                reason
        );
    }
}