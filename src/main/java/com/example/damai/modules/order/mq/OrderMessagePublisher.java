package com.example.damai.modules.order.mq;

import com.example.damai.common.config.RabbitMqConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OrderMessagePublisher {

    private final RabbitTemplate rabbitTemplate;

    // Spring AMQP 4 使用基于 Jackson 3 的 JSON 转换器
    private final MessageConverter messageConverter =
            new JacksonJsonMessageConverter(
                    "com.example.damai.modules.order.mq"
            );

    public OrderMessagePublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 将已经登记在 Redis Stream 中的建单任务发布到 RabbitMQ。
     *
     * 正常返回仅表示：
     * Broker 已确认发布，且未发生无法路由的 Return。
     *
     * 不代表 MySQL 已经创建订单。
     */
    public void publish(OrderCreateMessage orderMessage,
                        String outboxId) {

        Objects.requireNonNull(orderMessage, "orderMessage");

        if (outboxId == null || outboxId.isBlank()) {
            throw new IllegalArgumentException(
                    "Redis Stream 消息 ID 不能为空"
            );
        }

        // 1. 将 Java Record 转换为 JSON 消息
        MessageProperties properties = new MessageProperties();

        properties.setMessageId(orderMessage.requestId());
        properties.setHeader("outboxId", outboxId);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

        Message message = messageConverter.toMessage(
                orderMessage, properties
        );

        // 2. 使用 Stream 消息 ID 关联发布确认
        CorrelationData correlationData =
                new CorrelationData(outboxId);

        // 3. 发布到真实订单交换机
        rabbitTemplate.send(
                RabbitMqConfig.ORDER_EXCHANGE,
                RabbitMqConfig.ORDER_CREATE_ROUTING_KEY,
                message,
                correlationData
        );

        // 4. 等待 RabbitMQ Broker 确认
        try {
            CorrelationData.Confirm confirm =
                    correlationData.getFuture()
                            .get(10, TimeUnit.SECONDS);

            if (!confirm.ack()) {
                throw new IllegalStateException(
                        "RabbitMQ Broker NACK, outboxId="
                                + outboxId
                                + ", reason=" + confirm.reason()
                );
            }

            // 5. ACK 不代表一定成功路由到队列
            if (correlationData.getReturned() != null) {
                throw new IllegalStateException(
                        "RabbitMQ 消息无法路由到队列，outboxId="
                                + outboxId
                );
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "等待 RabbitMQ 发布确认被中断，outboxId="
                            + outboxId, e
            );

        } catch (ExecutionException | TimeoutException e) {

            throw new IllegalStateException(
                    "RabbitMQ 发布结果未确认，outboxId="
                            + outboxId, e
            );
        }
    }
}