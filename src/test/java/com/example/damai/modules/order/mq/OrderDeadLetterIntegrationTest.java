package com.example.damai.modules.order.mq;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "app.order.consumer.enabled=false",
        "app.order.relay.enabled=false"
})
class OrderDeadLetterIntegrationTest {

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void shouldDeadLetterExpiredMessage() {

        String suffix = UUID.randomUUID().toString();

        String source = "damai.test.dlx.source." + suffix;
        String dlq = "damai.test.dlq." + suffix;
        String dlx = "damai.test.dlx." + suffix;

        String routingKey = "test.failed";

        // 测试专用的死信交换机
        DirectExchange exchange = ExchangeBuilder
                .directExchange(dlx)
                .durable(true)
                .build();

        // 测试专用的死信队列
        Queue deadLetterQueue = QueueBuilder
                .durable(dlq)
                .quorum()
                .build();

        // 测试专用源队列
        // 消息超过 1 秒未被消费时自动过期并死信转发
        Queue sourceQueue = QueueBuilder
                .durable(source)
                .quorum()
                .ttl(1000)
                .deadLetterExchange(dlx)
                .deadLetterRoutingKey(routingKey)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-overflow", "reject-publish")
                .build();

        try {
            // 1. 创建隔离的测试资源
            admin.declareExchange(exchange);
            admin.declareQueue(deadLetterQueue);

            admin.declareBinding(
                    BindingBuilder.bind(deadLetterQueue)
                            .to(exchange)
                            .with(routingKey)
            );

            admin.declareQueue(sourceQueue);

            // 2. 向测试源队列发送消息
            String content = "DLX_TEST_" + suffix;

            // 默认交换机按队列名称直接路由
            rabbitTemplate.convertAndSend("", source, content);

            // 3. 等待 TTL 到期及死信转发
            Message received = rabbitTemplate.receive(dlq, 8000);

            assertNotNull(
                    received,
                    "消息没有成功进入死信队列"
            );

            String body = new String(
                    received.getBody(),
                    StandardCharsets.UTF_8
            );

            assertEquals(content, body);

        } finally {
            // 只清理本次测试创建的资源
            admin.deleteQueue(source);
            admin.deleteQueue(dlq);
            admin.deleteExchange(dlx);
        }
    }
}