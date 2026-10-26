package com.example.damai.modules.order.mq;

import com.example.damai.modules.order.service.AsyncOrderCreateService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCreateConsumerTest {

    @Mock
    private AsyncOrderCreateService service;

    @Mock
    private Channel channel;

    @InjectMocks
    private OrderCreateConsumer consumer;

    private Message message(long deliveryTag) {

        OrderCreateMessage payload = new OrderCreateMessage(
                10001L,
                UUID.randomUUID().toString(),
                90001L,
                2,
                new BigDecimal("199.00"),
                LocalDateTime.of(2027, 1, 1, 20, 0),
                LocalDateTime.of(2026, 10, 22, 17, 0)
        );

        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setMessageId(payload.requestId());
        properties.setHeader("outboxId", "12345-0");

        return new JacksonJsonMessageConverter(
                "com.example.damai.modules.order.mq"
        ).toMessage(payload, properties);
    }

    @Test
    void shouldAckAfterSuccess() throws Exception {

        Message raw = message(1L);

        consumer.onMessage(raw, channel);

        verify(service).process(
                any(OrderCreateMessage.class),
                eq("12345-0")
        );

        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(
                anyLong(), anyBoolean(), anyBoolean()
        );
    }

    @Test
    void shouldNackWhenBusinessFails() throws Exception {

        Message raw = message(2L);

        doThrow(new IllegalStateException("MySQL处理失败"))
                .when(service)
                .process(any(OrderCreateMessage.class), anyString());

        consumer.onMessage(raw, channel);

        verify(channel).basicNack(2L, false, false);

        verify(channel, never()).basicAck(
                anyLong(), anyBoolean()
        );
    }
}