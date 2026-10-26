package com.example.damai.common.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    // 订单交换机
    public static final String ORDER_EXCHANGE = "damai.order.exchange";

    // 创建订单队列
    public static final String ORDER_CREATE_QUEUE = "damai.order.create.queue";

    // 路由键
    public static final String ORDER_CREATE_ROUTING_KEY = "order.create";

    public static final String ORDER_DLX = "damai.order.dlx";

    public static final String ORDER_CREATE_DLQ = "damai.order.create.dlq";

    public static final String ORDER_FAILED_ROUTING_KEY = "order.create.failed";
    public static final String ORDER_CREATE_PARKING_QUEUE = "damai.order.create.parking.queue";

    @Bean
    public DirectExchange orderExchange() {
        return ExchangeBuilder
                .directExchange(ORDER_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue orderCreateQueue() {
        return QueueBuilder
                .durable(ORDER_CREATE_QUEUE)
                .quorum()
                .build();
    }

    @Bean
    public Binding orderCreateBinding(
            Queue orderCreateQueue,
            DirectExchange orderExchange) {

        return BindingBuilder
                .bind(orderCreateQueue)
                .to(orderExchange)
                .with(ORDER_CREATE_ROUTING_KEY);
    }

    /**
     * 订单异常消息交换机。
     */
    @Bean
    public DirectExchange orderDeadLetterExchange() {
        return ExchangeBuilder
                .directExchange(ORDER_DLX)
                .durable(true)
                .build();
    }

    /**
     * 订单异常消息队列。
     * 保留消息等待对账、恢复或人工处理。
     */
    @Bean
    public Queue orderCreateDlq() {
        return QueueBuilder
                .durable(ORDER_CREATE_DLQ)
                .quorum()
                .build();
    }

    /**
     * 死信交换机与死信队列绑定。
     */
    @Bean
    public Binding orderCreateDlqBinding(
            @Qualifier("orderCreateDlq") Queue dlq,
            @Qualifier("orderDeadLetterExchange") DirectExchange dlx) {

        return BindingBuilder
                .bind(dlq)
                .to(dlx)
                .with(ORDER_FAILED_ROUTING_KEY);
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.rabbitmq.init-on-startup",
            havingValue = "true"
    )
    public ApplicationRunner initializeRabbitResources(
            AmqpAdmin amqpAdmin) {

        return args -> {
            amqpAdmin.initialize();
            System.out.println("RabbitMQ 交换机、队列及绑定声明完成");
        };
    }

    /**
     * 订单异常任务暂存队列。
     *
     * 保存尚不能自动确认处理结果的订单消息。
     * 不配置自动消费者，也不设置消息过期时间。
     */
    @Bean
    public Queue orderCreateParkingQueue() {
        return QueueBuilder
                .durable(ORDER_CREATE_PARKING_QUEUE)
                .quorum()
                .build();
    }
}