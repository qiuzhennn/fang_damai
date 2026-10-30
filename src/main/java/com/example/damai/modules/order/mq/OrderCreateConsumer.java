package com.example.damai.modules.order.mq;

import com.example.damai.common.config.RabbitMqConfig;
import com.example.damai.modules.order.service.AsyncOrderCreateService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@ConditionalOnProperty(
        name = "app.order.consumer.enabled",
        havingValue = "true"
)
public class OrderCreateConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(OrderCreateConsumer.class);

    private final AsyncOrderCreateService orderCreateService;

    private final MessageConverter converter =
            new JacksonJsonMessageConverter(
                    "com.example.damai.modules.order.mq"
            );

    public OrderCreateConsumer(
            AsyncOrderCreateService orderCreateService) {

        this.orderCreateService = orderCreateService;
    }

    @RabbitListener(
            id = "damaiOrderCreateListener",
            queues = RabbitMqConfig.ORDER_CREATE_QUEUE,
            ackMode = "MANUAL",
            concurrency = "4"
    )
    public void onMessage(
            Message rawMessage,
            Channel channel) throws IOException {
        long deliveryTag =
                rawMessage.getMessageProperties().getDeliveryTag();

        String messageId =
                rawMessage.getMessageProperties().getMessageId();

        try {
            // 1. 从消息头取得 Redis Stream 任务 ID
            Object header = rawMessage.getMessageProperties()
                    .getHeaders().get("outboxId");

            if (!(header instanceof String outboxId)
                    || outboxId.isBlank()) {

                throw new IllegalArgumentException(
                        "RabbitMQ消息缺少outboxId"
                );
            }

            // 2. 将 JSON 还原为 Java Record
            Object decoded = converter.fromMessage(rawMessage);

            if (!(decoded instanceof OrderCreateMessage message)) {
                throw new IllegalArgumentException(
                        "RabbitMQ建单消息类型不正确"
                );
            }

            if (!message.requestId().equals(messageId)) {
                throw new IllegalArgumentException(
                        "RabbitMQ消息ID与requestId不一致"
                );
            }

            log.info(
                    "收到异步建单任务，requestId={}, outboxId={}",
                    message.requestId(),
                    outboxId
            );

            // 3. 按购买请求协调建单或复用已有订单，正常完成后再确认消息。
            orderCreateService.process(message, outboxId);

        } catch (Exception exception) {

            log.error(
                    "异步建单未确认完成，messageId={}，暂不ACK",
                    messageId,
                    exception
            );

            // 购买结果未确认，交给已配置的死信恢复流程核对，不立即重新入队。
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        // 4. 仅在业务处理正常完成后 ACK
        channel.basicAck(deliveryTag, false);

        log.info(
                "异步建单消息处理完成，messageId={}",
                messageId
        );
    }
}