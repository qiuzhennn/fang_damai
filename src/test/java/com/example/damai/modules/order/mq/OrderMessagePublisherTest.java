package com.example.damai.modules.order.mq;

import com.example.damai.common.config.RabbitMqConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderMessagePublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private OrderMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OrderMessagePublisher(rabbitTemplate);
    }

    private OrderCreateMessage message() {
        return new OrderCreateMessage(
                10001L,
                UUID.randomUUID().toString(),
                90001L,
                2,
                new BigDecimal("199.00"),
                LocalDateTime.of(2027, 1, 1, 20, 0),
                LocalDateTime.of(2026, 10, 22, 17, 0)
        );
    }

    @Test
    void shouldPublishWhenBrokerAck() {

        doAnswer(invocation -> {

            CorrelationData data = invocation.getArgument(3);

            data.getFuture().complete(
                    new CorrelationData.Confirm(true, null)
            );

            return null;

        }).when(rabbitTemplate).send(
                anyString(),
                anyString(),
                any(Message.class),
                any(CorrelationData.class)
        );

        assertDoesNotThrow(
                () -> publisher.publish(message(), "12345-0")
        );

        verify(rabbitTemplate).send(
                eq(RabbitMqConfig.ORDER_EXCHANGE),
                eq(RabbitMqConfig.ORDER_CREATE_ROUTING_KEY),
                any(Message.class),
                any(CorrelationData.class)
        );
    }

    @Test
    void shouldRejectBrokerNack() {

        doAnswer(invocation -> {

            CorrelationData data = invocation.getArgument(3);

            data.getFuture().complete(
                    new CorrelationData.Confirm(
                            false, "broker rejected"
                    )
            );

            return null;

        }).when(rabbitTemplate).send(
                anyString(),
                anyString(),
                any(Message.class),
                any(CorrelationData.class)
        );

        assertThrows(
                IllegalStateException.class,
                () -> publisher.publish(message(), "12345-0")
        );
    }
}