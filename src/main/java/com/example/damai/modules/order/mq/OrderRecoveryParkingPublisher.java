package com.example.damai.modules.order.mq;

import com.example.damai.common.config.RabbitMqConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class OrderRecoveryParkingPublisher {

    private final RabbitTemplate rabbitTemplate;

    public OrderRecoveryParkingPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 将异常订单消息转存到 Parking Queue。
     *
     * 仅在 Broker 确认发布且消息没有被退回时正常返回。
     */
    public void park(Message original, String reason) {

        MessageProperties old =
                original.getMessageProperties();

        MessageProperties properties =
                new MessageProperties();

        // 保留原来的消息身份
        properties.setMessageId(old.getMessageId());
        properties.setContentType(old.getContentType());
        properties.setDeliveryMode(
                MessageDeliveryMode.PERSISTENT
        );

        // 保留请求关联信息
        Object outboxId =
                old.getHeaders().get("outboxId");

        if (outboxId != null) {
            properties.setHeader("outboxId", outboxId);
        }

        Object typeId =
                old.getHeaders().get("__TypeId__");

        if (typeId != null) {
            properties.setHeader("__TypeId__", typeId);
        }

        // 标记本次隔离原因
        properties.setHeader("recoveryReason", reason);

        // 不修改原始消息体
        Message parked = new Message(
                original.getBody(),
                properties
        );

        CorrelationData correlationData =
                new CorrelationData(UUID.randomUUID().toString());

        rabbitTemplate.send(
                "",
                RabbitMqConfig.ORDER_CREATE_PARKING_QUEUE,
                parked,
                correlationData
        );

        try {
            var confirm = correlationData
                    .getFuture()
                    .get(10, TimeUnit.SECONDS);

            if (!confirm.ack()
                    || correlationData.getReturned() != null) {

                throw new IllegalStateException(
                        "异常订单消息转存未获确认"
                );
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "等待异常消息转存确认被中断", e
            );

        } catch (java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException e) {

            throw new IllegalStateException(
                    "异常消息转存结果未知", e
            );
        }
    }
}