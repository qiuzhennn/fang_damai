
package com.example.damai.modules.order.service;

import com.example.damai.modules.order.entity.AsyncOrderGuardSnapshot;
import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.order.mapper.AsyncOrderGuardMapper;
import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.ticket.redis.TicketRedisKeys;
import com.example.damai.modules.ticket.service.TicketStockReservationService;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * 异步订单库存补偿执行器。
 *
 * 职责：
 * 1. 验证MySQL Guard补偿资格。
 * 2. 核对原始购买信息。
 * 3. 确认MySQL没有创建订单。
 * 4. 执行Redis Lua幂等库存补偿。
 * 5. 确认Redis补偿结果。
 * 6. 将Guard状态从2更新为3。
 *
 * Guard状态：
 * 0 - 建单中
 * 1 - 订单已创建
 * 2 - 已取得补偿资格
 * 3 - 补偿已完成
 *
 * 注意：
 * 本类不负责决定是否应该补偿，
 * 只执行已经取得补偿资格的任务。
 *
 * MySQL和Redis之间不存在统一事务。
 */
@Service
public class AsyncOrderCompensationService {

    private final AsyncOrderGuardMapper guardMapper;

    private final TicketOrderService orderService;

    private final TicketStockReservationService reservationService;

    private final StringRedisTemplate redisTemplate;

    public AsyncOrderCompensationService(
            AsyncOrderGuardMapper guardMapper,
            TicketOrderService orderService,
            TicketStockReservationService reservationService,
            StringRedisTemplate redisTemplate) {

        this.guardMapper = guardMapper;
        this.orderService = orderService;
        this.reservationService = reservationService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 完成已经取得MySQL Guard补偿资格的任务。
     *
     * 前提：
     * Guard的status=2已经通过独立事务提交。
     *
     * 正常返回表示：
     * 1. Redis状态已经确认COMPENSATED。
     * 2. MySQL Guard状态已经确认是3。
     *
     * 发生异常时：
     * 不认为补偿已完成，
     * 由后续恢复机制重新核对MySQL和Redis状态。
     */
    public void completeClaimedCompensation(
            OrderCreateMessage message,
            String outboxId) {

        // 1. 校验输入参数
        validate(message, outboxId);

        Long userId = message.userId();
        String requestId = message.requestId();

        // ========================================
        // 第一步：查询MySQL Guard完整快照
        // ========================================

        AsyncOrderGuardSnapshot guard =
                guardMapper.findGuard(userId, requestId);

        // 核对消息与Guard中的原始购买信息
        if (!AsyncOrderGuardVerifier.matches(
                message,
                outboxId,
                guard)) {

            throw new IllegalStateException(
                    "补偿消息与MySQL Guard快照不一致，requestId="
                            + requestId
            );
        }

        Integer guardStatus = guard.status();

        // 只允许已取得补偿资格或已补偿完成的请求
        if (!Objects.equals(guardStatus, 2)
                && !Objects.equals(guardStatus, 3)) {

            throw new IllegalStateException(
                    "请求未获得补偿资格，guardStatus="
                            + guardStatus
                            + ", requestId="
                            + requestId
            );
        }

        // ========================================
        // 第二步：防御性核对MySQL订单
        // ========================================

        TicketOrder existing =
                orderService.findByRequestId(
                        userId,
                        requestId
                );

        // 已经有订单，绝对不能补偿库存
        if (existing != null) {

            throw new IllegalStateException(
                    "禁止补偿：MySQL已存在订单，orderId="
                            + existing.getId()
                            + ", requestId="
                            + requestId
            );
        }

        // ========================================
        // 第三步：读取Redis请求状态
        // ========================================

        String requestKey =
                TicketRedisKeys.request(userId, requestId);

        Map<Object, Object> state =
                readState(requestKey);

        // 验证Redis中的原始请求身份
        verifyRedisIdentity(message, outboxId, state);

        String redisStatus =
                text(state.get("status"));

        // ========================================
        // 第四步：Guard已经补偿完成
        // ========================================

        if (Objects.equals(guardStatus, 3)) {

            // Guard=3时，Redis也必须已经补偿
            if (!"COMPENSATED".equals(redisStatus)) {

                throw new IllegalStateException(
                        "Guard已完成，但Redis未确认补偿，"
                                + "requestId="
                                + requestId
                                + ", redisStatus="
                                + redisStatus
                );
            }

            // 两边均确认补偿完成，不再修改库存
            return;
        }

        // ========================================
        // 第五步：Guard=2，执行或恢复Redis补偿
        // ========================================

        if ("RESERVED".equals(redisStatus)) {

            /*
             * Redis仍处于预扣状态。
             *
             * 调用已有的库存补偿服务，
             * 由Lua原子判断是否能够补偿。
             *
             * Lua必须保证：
             * RESERVED -> COMPENSATED 才增加库存。
             * COMPENSATED再次调用不能重复增加库存。
             */
            reservationService.compensate(
                    userId,
                    requestId,
                    message.ticketCategoryId(),
                    message.quantity()
            );

            /*
             * 不仅依赖Java方法正常返回。
             *
             * 重新读取Redis，确认库存补偿
             * 对应的请求状态已经改变。
             */
            state = readState(requestKey);

            verifyRedisIdentity(
                    message,
                    outboxId,
                    state
            );

            redisStatus =
                    text(state.get("status"));
        }

        /*
         * 如果Redis原本已经是COMPENSATED，
         * 上面的Lua调用会直接跳过。
         *
         * 这样可以恢复以下场景：
         *
         * 1. Redis补偿成功。
         * 2. 应用突然崩溃。
         * 3. MySQL Guard仍然是2。
         * 4. 应用重启后重新执行本方法。
         *
         * 此时只需要完成Guard更新。
         */

        // ========================================
        // 第六步：确认Redis已经补偿
        // ========================================

        if (!"COMPENSATED".equals(redisStatus)) {

            throw new IllegalStateException(
                    "Redis库存补偿结果未确认，requestId="
                            + requestId
                            + ", redisStatus="
                            + redisStatus
            );
        }

        // ========================================
        // 第七步：更新MySQL Guard为3
        // ========================================

        int updated =
                guardMapper.finishCompensation(
                        userId,
                        requestId,
                        outboxId
                );

        if (updated == 1) {
            // Guard从2成功更新为3
            return;
        }

        /*
         * 更新返回0时，可能存在并发恢复：
         * 其他线程已经提前完成了Guard更新。
         *
         * 因此重新查询状态，不直接认定失败。
         */
        Integer latestStatus =
                guardMapper.findStatus(
                        userId,
                        requestId
                );

        if (!Objects.equals(latestStatus, 3)) {

            throw new IllegalStateException(
                    "Guard补偿完成状态更新失败，requestId="
                            + requestId
                            + ", latestStatus="
                            + latestStatus
            );
        }

        // Guard已经是3，补偿流程确认完成
    }

    /**
     * 读取Redis请求Hash。
     *
     * 不存在时必须抛出异常，
     * 不能默认认为库存可以补偿。
     */
    private Map<Object, Object> readState(
            String requestKey) {

        Map<Object, Object> state =
                redisTemplate.opsForHash()
                        .entries(requestKey);

        if (state == null || state.isEmpty()) {

            throw new IllegalStateException(
                    "Redis预扣记录不存在："
                            + requestKey
            );
        }

        return state;
    }

    /**
     * 验证Redis预扣记录与原始订单消息一致。
     *
     * 防止：
     * 1. 同一requestId被错误关联其他任务。
     * 2. 不同票档的库存被错误补偿。
     * 3. 购买数量被修改。
     * 4. 同步请求误入异步补偿流程。
     */
    private void verifyRedisIdentity(
            OrderCreateMessage message,
            String outboxId,
            Map<Object, Object> state) {

        String mode = text(state.get("mode"));

        String redisOutboxId =
                text(state.get("outboxId"));

        String redisCategoryId =
                text(state.get("ticketCategoryId"));

        String redisQuantity =
                text(state.get("quantity"));

        if (!"ASYNC".equals(mode)
                || !outboxId.equals(redisOutboxId)
                || !message.ticketCategoryId()
                .toString()
                .equals(redisCategoryId)
                || !message.quantity()
                .toString()
                .equals(redisQuantity)) {

            throw new IllegalStateException(
                    "Redis预扣身份校验失败，requestId="
                            + message.requestId()
            );
        }
    }

    /**
     * 校验补偿任务的基础参数。
     *
     * 使用分段校验，方便定位具体错误。
     */
    private void validate(
            OrderCreateMessage message,
            String outboxId) {

        // 1. 消息不能为空
        if (message == null) {

            throw new IllegalArgumentException(
                    "补偿消息不能为空"
            );
        }

        // 2. 用户ID
        if (message.userId() == null
                || message.userId() <= 0) {

            throw new IllegalArgumentException(
                    "用户ID不合法："
                            + message.userId()
            );
        }

        // 3. requestId必须为UUID格式
        String requestId = message.requestId();

        if (requestId == null
                || !requestId.matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        )) {

            throw new IllegalArgumentException(
                    "requestId格式不合法："
                            + requestId
            );
        }

        // 4. 票档ID
        if (message.ticketCategoryId() == null
                || message.ticketCategoryId() <= 0) {

            throw new IllegalArgumentException(
                    "票档ID不合法："
                            + message.ticketCategoryId()
            );
        }

        // 5. 购票数量
        if (message.quantity() == null
                || message.quantity() < 1
                || message.quantity() > 6) {

            throw new IllegalArgumentException(
                    "购票数量不合法："
                            + message.quantity()
            );
        }

        // 6. 单价快照
        BigDecimal unitPrice = message.unitPrice();

        if (unitPrice == null
                || unitPrice.signum() < 0
                || unitPrice.scale() > 2
                || unitPrice.compareTo(
                new BigDecimal("9999999999.99")
        ) > 0) {

            throw new IllegalArgumentException(
                    "价格快照不合法："
                            + unitPrice
            );
        }

        // 7. 时间快照不能为空
        if (message.eventStartTime() == null
                || message.acceptedAt() == null) {

            throw new IllegalArgumentException(
                    "演出时间或受理时间不能为空"
            );
        }

        // 8. 受理时间必须早于演出开始时间
        if (!message.acceptedAt()
                .isBefore(message.eventStartTime())) {

            throw new IllegalArgumentException(
                    "时间快照不合法，acceptedAt="
                            + message.acceptedAt()
                            + ", eventStartTime="
                            + message.eventStartTime()
            );
        }

        // 9. Redis Stream消息ID
        if (outboxId == null
                || !outboxId.matches(
                "^[0-9]+-[0-9]+$"
        )) {

            throw new IllegalArgumentException(
                    "outboxId格式不合法："
                            + outboxId
            );
        }
    }

    /**
     * 将Redis字段值统一转换成字符串。
     */
    private String text(Object value) {
        return value == null
                ? null
                : value.toString();
    }
}
