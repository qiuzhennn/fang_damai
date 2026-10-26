package com.example.damai.modules.order.mq;

import com.example.damai.modules.order.service.OrderDeadLetterRecoveryService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class OrderDeadLetterConsumerTest {

    @Mock
    private OrderDeadLetterRecoveryService recoveryService;

    @Mock
    private OrderRecoveryParkingPublisher parkingPublisher;

    @Mock
    private Channel channel;

    @InjectMocks
    private OrderDeadLetterConsumer consumer;

    private Message createMessage() {

        OrderCreateMessage payload = new OrderCreateMessage(
                10001L,
                UUID.randomUUID().toString(),
                90001L,
                2,
                new BigDecimal("199.00"),
                LocalDateTime.of(2027, 1, 1, 20, 0),
                LocalDateTime.of(2026, 10, 23, 10, 0)
        );

        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(1L);
        props.setMessageId(payload.requestId());
        props.setHeader("outboxId", "12345-0");

        return new JacksonJsonMessageConverter(
                "com.example.damai.modules.order.mq"
        ).toMessage(payload, props);
    }

    @Test
    void shouldAckWhenAlreadyCreated() throws Exception {

        when(recoveryService.reconcile(
                any(OrderCreateMessage.class), eq("12345-0")
        )).thenReturn(OrderRecoveryDecision.ALREADY_CREATED);

        consumer.onMessage(createMessage(), channel);

        verify(channel).basicAck(1L, false);
        verifyNoInteractions(parkingPublisher);
    }

    @Test
    void shouldAckWhenRedisRepaired() throws Exception {

        when(recoveryService.reconcile(
                any(OrderCreateMessage.class), eq("12345-0")
        )).thenReturn(OrderRecoveryDecision.REDIS_REPAIRED);

        consumer.onMessage(createMessage(), channel);

        verify(channel).basicAck(1L, false);
        verifyNoInteractions(parkingPublisher);
    }

    @Test
    void shouldParkBeforeAckWhenRetryRequired() throws Exception {

        when(recoveryService.reconcile(
                any(OrderCreateMessage.class), eq("12345-0")
        )).thenReturn(OrderRecoveryDecision.RETRY_REQUIRED);

        Message raw = createMessage();
        consumer.onMessage(raw, channel);

        InOrder order = inOrder(parkingPublisher, channel);

        // 先确认消息已经转存
        order.verify(parkingPublisher).park(
                same(raw), eq("RETRY_REQUIRED")
        );

        // 再 ACK 原来的 DLQ 消息
        order.verify(channel).basicAck(1L, false);
    }

    @Test
    void shouldParkWhenManualReviewRequired() throws Exception {

        when(recoveryService.reconcile(
                any(OrderCreateMessage.class), eq("12345-0")
        )).thenReturn(OrderRecoveryDecision.MANUAL_REVIEW);

        consumer.onMessage(createMessage(), channel);

        verify(parkingPublisher).park(
                any(Message.class), eq("MANUAL_REVIEW")
        );

        verify(channel).basicAck(1L, false);
    }

    @Test
    void shouldNotAckWhenParkingFails() throws Exception {

        when(recoveryService.reconcile(
                any(OrderCreateMessage.class), eq("12345-0")
        )).thenReturn(OrderRecoveryDecision.RETRY_REQUIRED);

        doThrow(new IllegalStateException("MQ暂存失败"))
                .when(parkingPublisher)
                .park(any(Message.class), anyString());

        consumer.onMessage(createMessage(), channel);

        // 原始消息必须保持未确认
        verify(channel, never()).basicAck(
                anyLong(), anyBoolean()
        );

        verify(channel, never()).basicNack(
                anyLong(), anyBoolean(), anyBoolean()
        );
    }
}