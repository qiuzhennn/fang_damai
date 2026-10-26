
package com.example.damai.modules.order.service;

import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.ticket.redis.TicketRedisKeys;
import com.example.damai.modules.ticket.service.TicketStockReservationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/**
 * RabbitMQ 异步建单业务协调层。
 *
 * 职责：
 * 1. 校验消息及 Redis 预扣记录
 * 2. 查询 MySQL，处理重复消费
 * 3. 根据受理时的消息快照创建订单
 * 4. 在 MySQL 提交后回写 Redis 状态
 * 5. 核对 Redis 最终状态
 *
 * 注意：
 * 本类不添加 @Transactional。
 * MySQL 建单事务由 TicketOrderService.createFromReservation()
 * 独立管理。
 */
@Service
public class AsyncOrderCreateService {

    private final TicketOrderService orderService;

    private final TicketStockReservationService reservationService;

    private final StringRedisTemplate redisTemplate;

    public AsyncOrderCreateService(
            TicketOrderService orderService,
            TicketStockReservationService reservationService,
            StringRedisTemplate redisTemplate) {

        this.orderService = orderService;
        this.reservationService = reservationService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 处理已经完成 Redis 库存预扣的异步建单消息。
     *
     * 正常返回表示：
     * 1. 已确认原请求没有有效建单资格；
     *    或者
     * 2. MySQL 订单已经存在，且 Redis 成功记录建单结果。
     *
     * 出现异常时交给 MQ 消费者处理，
     * 这里不直接执行库存补偿。
     */
    public void process(
            OrderCreateMessage message,
            String outboxId) {

        // 1. 校验消息基本参数
        validateMessage(message, outboxId);

        // 2. 生成本次购买的 Redis 请求 Key
        String requestKey = TicketRedisKeys.request(
                message.userId(),
                message.requestId()
        );

        // 3. 读取 Redis 预扣记录
        Map<Object, Object> state =
                redisTemplate.opsForHash().entries(requestKey);

        // 4. 校验消息与原预扣记录是否一致
        verifyReservation(message, outboxId, state);

        String status = text(state.get("status"));

        // 5. 根据 userId + requestId 查询 MySQL
        TicketOrder existing = orderService.findByRequestId(
                message.userId(),
                message.requestId()
        );

        /*
         * 情况一：
         * Redis 已补偿。
         *
         * 如果 MySQL 没有订单，说明此请求已经关闭，
         * 本次消息不允许重新创建订单。
         *
         * 如果 MySQL 存在订单，说明数据不一致，
         * 必须交给异常恢复流程核查。
         */
        if ("COMPENSATED".equals(status)) {

            if (existing != null) {
                throw new IllegalStateException(
                        "Redis已补偿，但MySQL存在订单，orderId="
                                + existing.getId()
                );
            }

            return;
        }

        /*
         * 情况二：
         * MySQL 已经存在订单。
         *
         * 可能是：
         * - RabbitMQ 重复投递
         * - MySQL 提交成功，但 Redis 回写失败
         * - 消费者在 ACK 前崩溃
         *
         * 不能再次扣减 MySQL 库存或创建订单。
         */
        if (existing != null) {

            verifyExistingOrder(existing, message);

            // Redis 显示已创建时，也需要核对真实订单 ID
            if ("ORDER_CREATED".equals(status)) {

                String recordedOrderId =
                        text(state.get("orderId"));

                if (!existing.getId().toString()
                        .equals(recordedOrderId)) {

                    throw new IllegalStateException(
                            "Redis订单ID与MySQL不一致，requestId="
                                    + message.requestId()
                    );
                }

                return;
            }

            if (!"RESERVED".equals(status)) {
                throw new IllegalStateException(
                        "MySQL已有订单，但Redis状态异常："
                                + status
                );
            }

            // MySQL 已确认存在订单，修复 Redis
            markCreatedAndVerify(
                    message,
                    outboxId,
                    requestKey,
                    existing.getId()
            );

            return;
        }

        /*
         * 情况三：
         * Redis 显示 ORDER_CREATED，
         * 但 MySQL 查不到订单。
         *
         * 不能重新建单，更不能直接补库存。
         */
        if ("ORDER_CREATED".equals(status)) {

            throw new IllegalStateException(
                    "Redis显示订单已创建，但MySQL未查到订单，requestId="
                            + message.requestId()
            );
        }

        /*
         * 情况四：
         * 没有 MySQL 订单，Redis 仍处于 RESERVED。
         *
         * 这是正常的首次异步建单入口。
         */
        if (!"RESERVED".equals(status)) {

            throw new IllegalStateException(
                    "非法预扣状态：" + status
            );
        }

        Long orderId;

        try {

            /*
             * 6. 使用受理时的价格快照创建 MySQL 订单。
             *
             * 不再调用 validatePurchase()。
             *
             * createFromReservation() 内部使用 @Transactional，
             * 正常返回后，MySQL 事务已经提交。
             */
            orderId = orderService.createFromReservation(
                    message, outboxId
            );

        } catch (DuplicateKeyException exception) {

            /*
             * 7. 并发重复消费可能触发数据库唯一键冲突。
             *
             * 重新查询原订单，确认是否已成功提交。
             */
            TicketOrder duplicated =
                    orderService.findByRequestId(
                            message.userId(),
                            message.requestId()
                    );

            if (duplicated == null) {

                // 结果暂时无法确认，不补偿库存
                throw exception;
            }

            // 防止相同 requestId 对应不同购买内容
            verifyExistingOrder(duplicated, message);

            orderId = duplicated.getId();
        }

        /*
         * 8. MySQL 已经确认创建成功。
         *
         * 将 Redis 状态修改为 ORDER_CREATED，
         * 并重新读取核对。
         *
         * 只有这里正常返回，
         * RabbitMQ Consumer 才可以 ACK。
         */
        markCreatedAndVerify(
                message,
                outboxId,
                requestKey,
                orderId
        );
    }

    /**
     * 校验异步建单消息的基本参数。
     */
    private void validateMessage(
            OrderCreateMessage message,
            String outboxId) {

        if (message == null
                || message.userId() == null
                || message.userId() <= 0
                || message.ticketCategoryId() == null
                || message.ticketCategoryId() <= 0
                || message.quantity() == null
                || message.quantity() < 1
                || message.quantity() > 6
                || message.requestId() == null
                || !message.requestId().matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        )
                || message.unitPrice() == null
                || message.unitPrice().signum() < 0
                || message.eventStartTime() == null
                || message.acceptedAt() == null
                || !message.acceptedAt()
                .isBefore(message.eventStartTime())
                || outboxId == null
                || !outboxId.matches("^[0-9]+-[0-9]+$")) {

            throw new IllegalArgumentException(
                    "异步建单消息参数不合法"
            );
        }
    }

    /**
     * 校验 RabbitMQ 消息与 Redis 预扣记录一致。
     *
     * 防止处理：
     * - 未经过 Redis 预扣的消息
     * - 其他请求的消息
     * - 票档、数量不一致的消息
     * - 同步模式的请求
     */
    private void verifyReservation(
            OrderCreateMessage message,
            String outboxId,
            Map<Object, Object> state) {

        if (state == null || state.isEmpty()) {

            throw new IllegalStateException(
                    "Redis预扣请求记录不存在，requestId="
                            + message.requestId()
            );
        }

        if (!"ASYNC".equals(text(state.get("mode")))
                || !outboxId.equals(
                text(state.get("outboxId"))
        )
                || !message.ticketCategoryId().toString()
                .equals(
                        text(state.get("ticketCategoryId"))
                )
                || !message.quantity().toString()
                .equals(
                        text(state.get("quantity"))
                )) {

            throw new IllegalStateException(
                    "RabbitMQ消息与Redis预扣记录不一致，requestId="
                            + message.requestId()
            );
        }
    }

    /**
     * 校验已存在的 MySQL 订单。
     *
     * RabbitMQ 可能重复投递消息，
     * 但同一 requestId 不允许创建不同内容的订单。
     */
    private void verifyExistingOrder(
            TicketOrder order,
            OrderCreateMessage message) {

        if (!Objects.equals(
                order.getUserId(),
                message.userId()
        )
                || !Objects.equals(
                order.getRequestId(),
                message.requestId()
        )
                || !Objects.equals(
                order.getTicketCategoryId(),
                message.ticketCategoryId()
        )
                || !Objects.equals(
                order.getQuantity(),
                message.quantity()
        )
                || order.getUnitPrice() == null
                || order.getUnitPrice().compareTo(
                message.unitPrice()
        ) != 0) {

            throw new IllegalStateException(
                    "相同requestId对应的MySQL订单参数冲突，requestId="
                            + message.requestId()
            );
        }
    }

    /**
     * MySQL 订单确认成功后，回写并核对 Redis。
     *
     * markOrderCreated 必须通过 Lua 原子检查状态，
     * 不能将 COMPENSATED 无条件覆盖成 ORDER_CREATED。
     */
    private void markCreatedAndVerify(
            OrderCreateMessage message,
            String outboxId,
            String requestKey,
            Long orderId) {

        // 1. 使用已有的 Redis Lua 状态更新方法
        reservationService.markOrderCreated(
                message.userId(),
                message.requestId(),
                message.ticketCategoryId(),
                message.quantity(),
                orderId
        );

        // 2. 重新读取实际 Redis 状态
        Map<Object, Object> latestState =
                redisTemplate.opsForHash().entries(requestKey);

        // 3. 再次核对请求身份
        verifyReservation(
                message,
                outboxId,
                latestState
        );

        String latestStatus =
                text(latestState.get("status"));

        String latestOrderId =
                text(latestState.get("orderId"));

        // 4. 必须与已提交的 MySQL 订单一致
        if (!"ORDER_CREATED".equals(latestStatus)
                || !orderId.toString().equals(latestOrderId)) {

            throw new IllegalStateException(
                    "MySQL订单已创建，但Redis结果回写未确认，"
                            + "requestId=" + message.requestId()
                            + ", orderId=" + orderId
                            + ", redisStatus=" + latestStatus
            );
        }
    }

    /**
     * Redis Hash 字段转换为字符串。
     */
    private String text(Object value) {
        return value == null ? null : value.toString();
    }
}

