
package com.example.damai.modules.order.service;

import com.example.damai.modules.order.entity.AsyncOrderGuardSnapshot;
import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.order.mapper.AsyncOrderGuardMapper;
import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.ticket.redis.TicketRedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/**
 * 异步订单补偿资格申请服务。
 *
 * 目前仅支持：
 * 演出已经开始，而订单尚未成功创建的场景。
 *
 * 本类只负责取得MySQL Guard补偿资格，不执行Redis库存回补。
 */
@Service
public class AsyncOrderCompensationClaimService {

    private final AsyncOrderGuardMapper guardMapper;
    private final TicketOrderService orderService;
    private final StringRedisTemplate redisTemplate;

    public AsyncOrderCompensationClaimService(
            AsyncOrderGuardMapper guardMapper,
            TicketOrderService orderService,
            StringRedisTemplate redisTemplate) {

        this.guardMapper = guardMapper;
        this.orderService = orderService;
        this.redisTemplate = redisTemplate;
    }


    /**
     * 为演出已经开始的异步请求申请或恢复补偿资格。
     *
     * 本方法只操作补偿资格，不直接修改Redis库存。
     *
     * 通过Spring事务代理正常返回后，
     * 本次MySQL Guard事务才视为提交成功。
     */
    @Transactional
    public CompensationClaimResult claimForExpiredEvent(
            OrderCreateMessage message,
            String outboxId) {

        validate(message, outboxId);

        LocalDateTime now =
                LocalDateTime.now(ZoneId.of("Asia/Shanghai"));

        if (now.isBefore(message.eventStartTime())) {
            return CompensationClaimResult.NOT_EXPIRED;
        }

        Long userId = message.userId();
        String requestId = message.requestId();

        String requestKey =
                TicketRedisKeys.request(userId, requestId);

        // 1. 读取并核验原始预扣记录
        Map<Object, Object> state =
                redisTemplate.opsForHash().entries(requestKey);

        if (state == null || state.isEmpty()) {
            return CompensationClaimResult.MANUAL_REVIEW;
        }

        if (!"ASYNC".equals(text(state.get("mode")))
                || !outboxId.equals(text(state.get("outboxId")))
                || !message.ticketCategoryId().toString()
                .equals(text(state.get("ticketCategoryId")))
                || !message.quantity().toString()
                .equals(text(state.get("quantity")))) {

            return CompensationClaimResult.MANUAL_REVIEW;
        }

        String redisStatus = text(state.get("status"));

        /*
         * 2. Redis已经补偿的恢复分支。
         *
         * 不能再调用claimCompensation()创建新资格，
         * 必须确认原MySQL Guard确实允许补偿。
         */

        if ("COMPENSATED".equals(redisStatus)) {

            AsyncOrderGuardSnapshot guard =
                    findMatchingGuard(message, outboxId);

            if (guard == null) {
                return CompensationClaimResult.MANUAL_REVIEW;
            }

            if (Objects.equals(guard.status(), 2)) {
                return CompensationClaimResult.ALREADY_CLAIMED;
            }

            if (Objects.equals(guard.status(), 3)) {
                return CompensationClaimResult.ALREADY_COMPLETED;
            }

            return CompensationClaimResult.MANUAL_REVIEW;
        }


        /*
         * 3. Redis已经创建订单。
         *
         * 只有MySQL Guard也记录了建单成功，
         * 才交给后续订单恢复服务核对。
         */

        if ("ORDER_CREATED".equals(redisStatus)) {

            AsyncOrderGuardSnapshot guard =
                    findMatchingGuard(message, outboxId);

            if (guard == null) {
                return CompensationClaimResult.MANUAL_REVIEW;
            }

            if (Objects.equals(guard.status(), 1)) {
                return CompensationClaimResult.ORDER_CREATED;
            }

            return CompensationClaimResult.MANUAL_REVIEW;
        }


        /*
         * 4. 只有RESERVED允许首次竞争补偿资格。
         */
        if (!"RESERVED".equals(redisStatus)) {
            return CompensationClaimResult.MANUAL_REVIEW;
        }

        int claimed = guardMapper.claimCompensation(
                userId,
                requestId,
                message.ticketCategoryId(),
                message.quantity(),
                outboxId,
                message.unitPrice(),
                message.eventStartTime()
        );

        /*
         * 5. 本事务首次取得补偿资格。
         */

        if (claimed == 1) {

            // Guard刚在当前事务中插入，核对写入的完整快照
            AsyncOrderGuardSnapshot guard =
                    findMatchingGuard(message, outboxId);

            if (guard == null
                    || !Objects.equals(guard.status(), 2)) {

                // 必须抛异常，使当前MySQL事务回滚
                throw new IllegalStateException(
                        "新插入的Guard快照校验失败，requestId="
                                + requestId
                );
            }

            // Guard取得补偿资格后再次核对原订单
            TicketOrder existing =
                    orderService.findByRequestId(
                            userId,
                            requestId
                    );

            if (existing != null) {
                throw new IllegalStateException(
                        "MySQL已有订单，禁止申请补偿资格，orderId="
                                + existing.getId()
                );
            }

            return CompensationClaimResult.CLAIMED;
        }


        if (claimed != 0) {
            throw new IllegalStateException(
                    "Guard申请返回异常结果：" + claimed
            );
        }

        /*
         * 6. 已存在Guard记录。
         *
         * INSERT IGNORE已解决并发唯一键竞争。
         * 现在核对已提交的处理结果。
         */

        AsyncOrderGuardSnapshot guard =
                findMatchingGuard(message, outboxId);

        if (guard == null) {
            return CompensationClaimResult.MANUAL_REVIEW;
        }

        if (Objects.equals(guard.status(), 1)) {
            return CompensationClaimResult.ORDER_CREATED;
        }

        if (Objects.equals(guard.status(), 2)) {
            return CompensationClaimResult.ALREADY_CLAIMED;
        }
        // Guard=3但Redis仍为RESERVED，属于数据冲突
        return CompensationClaimResult.MANUAL_REVIEW;




    }



    /**
     * 校验异步订单补偿资格申请参数。
     *
     * 将不同字段的校验拆开，
     * 便于准确定位异常原因。
     */
    private void validate(
            OrderCreateMessage message,
            String outboxId) {

        if (message == null) {
            throw new IllegalArgumentException("补偿消息不能为空");
        }

        if (!message.hasValidFields()) {
            throw new IllegalArgumentException(
                    "补偿消息中的购买资料不合法"
            );
        }

        if (outboxId == null || !outboxId.matches("^[0-9]+-[0-9]+$")) {
            throw new IllegalArgumentException(
                    "补偿任务标识不合法：" + outboxId
            );
        }
    }


    private String text(Object value) {
        return value == null ? null : value.toString();
    }


    /**
     * 查询并核验MySQL Guard完整快照。
     *
     * 匹配成功：返回Guard。
     * 匹配失败或不存在：返回null。
     */
    private AsyncOrderGuardSnapshot findMatchingGuard(
            OrderCreateMessage message,
            String outboxId) {

        AsyncOrderGuardSnapshot guard =
                guardMapper.findGuard(
                        message.userId(),
                        message.requestId()
                );

        if (!AsyncOrderGuardVerifier.matches(
                message,
                outboxId,
                guard)) {

            return null;
        }

        return guard;
    }

}
