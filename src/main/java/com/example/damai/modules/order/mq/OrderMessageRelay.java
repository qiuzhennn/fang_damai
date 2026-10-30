package com.example.damai.modules.order.mq;

import com.example.damai.modules.ticket.redis.TicketRedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "app.order.relay.enabled",
        havingValue = "true"
)
public class OrderMessageRelay {

    private static final Logger log =
            LoggerFactory.getLogger(OrderMessageRelay.class);

    // Redis Stream Key
    private static final String STREAM =
            TicketRedisKeys.orderCreateOutboxStream();

    // 同一个逻辑消费组，多个实例可共同处理任务
    private static final String GROUP =
            "damai-order-relay-v1";

    // Pending 任务的最小空闲时间
    private static final Duration MIN_IDLE =
            Duration.ofMinutes(2);
    // 增大单批领取数量，减少抢票高峰时任务跨轮等待的次数。
    private static final int BATCH_SIZE = 50;

    private final StringRedisTemplate redisTemplate;
    private final OrderMessagePublisher publisher;

    // 每个进程使用不同的消费者名称
    private final String consumerName =
            "relay-" + UUID.randomUUID();

    private volatile boolean groupReady = false;

    public OrderMessageRelay(
            StringRedisTemplate redisTemplate,
            OrderMessagePublisher publisher) {

        this.redisTemplate = redisTemplate;
        this.publisher = publisher;
    }

    /**
     * 定时执行一轮任务转发。
     *
     * 先恢复 Pending，再领取新任务。
     * 此处只实现单轮批处理，后续按压测结果扩容。
     */
    @Scheduled(
            fixedDelayString = "${app.order.relay.poll-ms:1000}"
    )
    public void runOnce() {

        try {
            ensureGroup();

            recoverPending();

            readNewMessages();

        } catch (Exception e) {
            log.error("订单消息 Relay 本轮执行失败", e);
        }
    }

    /**
     * 初始化消费组。
     *
     * 使用 0-0，而不是 $，
     * 防止消费组创建前已有消息被跳过。
     */
    private void ensureGroup() {

        if (groupReady) {
            return;
        }

        try {
            redisTemplate.opsForStream().createGroup(
                    STREAM,
                    ReadOffset.from("0-0"),
                    GROUP
            );

            log.info("Redis Stream 消费组创建成功：{}", GROUP);

        } catch (RuntimeException e) {

            if (!isBusyGroup(e)) {
                throw e;
            }

            // 消费组已经存在，正常复用
            log.info("Redis Stream 消费组已存在：{}", GROUP);
        }

        groupReady = true;
    }

    private boolean isBusyGroup(Throwable throwable) {

        while (throwable != null) {

            String message = throwable.getMessage();

            if (message != null &&
                    message.contains("BUSYGROUP")) {
                return true;
            }

            throwable = throwable.getCause();
        }

        return false;
    }

    /**
     * 读取尚未被消费组领取的新消息。
     */
    private void readNewMessages() {

        List<MapRecord<String, Object, Object>> records =
                redisTemplate.opsForStream().read(
                        Consumer.from(GROUP, consumerName),
                        StreamReadOptions.empty()
                                .count(BATCH_SIZE),
                        StreamOffset.create(
                                STREAM,
                                ReadOffset.lastConsumed()
                        )
                );

        processBatch(records);
    }

    /**
     * 恢复超过最小空闲时间的 Pending 消息。
     *
     * 使用 XPENDING + XCLAIM。
     * 效果类似 XAUTOCLAIM，但这里使用 Spring Data
     * Redis 4.0 已提供的 API。
     */
    private void recoverPending() {

        PendingMessages pending =
                redisTemplate.opsForStream().pending(
                        STREAM,
                        GROUP,
                        Range.unbounded(),
                        BATCH_SIZE,
                        MIN_IDLE
                );

        if (pending == null || pending.isEmpty()) {
            return;
        }

        RecordId[] ids = pending.stream()
                .map(PendingMessage::getId)
                .toArray(RecordId[]::new);

        List<MapRecord<String, Object, Object>> claimed =
                redisTemplate.opsForStream().claim(
                        STREAM,
                        GROUP,
                        consumerName,
                        MIN_IDLE,
                        ids
                );

        processBatch(claimed);
    }

    private void processBatch(
            List<MapRecord<String, Object, Object>> records) {

        if (records == null || records.isEmpty()) {
            return;
        }

        for (MapRecord<String, Object, Object> record : records) {
            try {
                processOne(record);
            } catch (Exception e) {
                // 当前任务未确认完成转发，保留 Pending，供后续核对或重试。
                log.error(
                        "建单任务转发失败，outboxId={}",
                        record.getId().getValue(),
                        e
                );
            }
        }
    }

    /**
     * 处理单条待转发任务。
     */
    private void processOne(
            MapRecord<String, Object, Object> record) {

        String outboxId = record.getId().getValue();

        Map<Object, Object> fields = record.getValue();

        String requestKey = required(fields, "requestKey");

        OrderCreateMessage message = new OrderCreateMessage(
                Long.valueOf(required(fields, "userId")),
                required(fields, "requestId"),
                Long.valueOf(required(fields, "ticketCategoryId")),
                Integer.valueOf(required(fields, "quantity")),
                new BigDecimal(required(fields, "unitPrice")),
                LocalDateTime.parse(
                        required(fields, "eventStartTime")
                ),
                LocalDateTime.parse(
                        required(fields, "acceptedAt")
                )
        );

        // Stream 里的 requestKey 必须与消息身份一致
        String expectedRequestKey = TicketRedisKeys.request(
                message.userId(),
                message.requestId()
        );

        if (!Objects.equals(requestKey, expectedRequestKey)) {
            throw new IllegalStateException(
                    "Stream 任务的请求身份不一致"
            );
        }

        // 检查 Redis 当前的预扣请求记录
        Map<Object, Object> state =
                redisTemplate.opsForHash().entries(requestKey);

        String mode = text(state.get("mode"));
        String status = text(state.get("status"));
        String recordedOutboxId = text(state.get("outboxId"));

        // 只允许转发有效的异步任务
        if (!"ASYNC".equals(mode)
                || !outboxId.equals(recordedOutboxId)
                || !Objects.equals(
                text(state.get("ticketCategoryId")),
                message.ticketCategoryId().toString()
        )
                || !Objects.equals(
                text(state.get("quantity")),
                message.quantity().toString()
        )) {

            // 异常任务留在 Pending，等待对账或人工处理
            throw new IllegalStateException(
                    "请求记录与 Stream 任务不一致，outboxId="
                            + outboxId
            );
        }

        if ("ORDER_CREATED".equals(status)
                || "COMPENSATED".equals(status)) {

            // 请求已经结束，无需再次发送消息
            acknowledge(record);
            return;
        }

        if (!"RESERVED".equals(status)) {
            throw new IllegalStateException(
                    "未知的请求状态：" + status
            );
        }

        // 等待 RabbitMQ 确认任务已成功发布，失败时由上层保留 Pending。
        publisher.publish(message, outboxId);

        // 发布已确认后，再确认这条 Stream 任务完成转发。
        acknowledge(record);

        log.info(
                "建单任务成功转发 RabbitMQ，outboxId={}, requestId={}",
                outboxId,
                message.requestId()
        );
    }

    private void acknowledge(
            MapRecord<String, Object, Object> record) {

        Long count = redisTemplate.opsForStream().acknowledge(
                STREAM,
                GROUP,
                record.getId()
        );

        if (!Long.valueOf(1).equals(count)) {
            log.warn(
                    "Stream XACK 数量异常，outboxId={}, count={}",
                    record.getId().getValue(),
                    count
            );
        }
    }

    private static String required(
            Map<Object, Object> fields,
            String name) {

        String value = text(fields.get(name));

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Stream 消息缺少字段：" + name
            );
        }

        return value;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
