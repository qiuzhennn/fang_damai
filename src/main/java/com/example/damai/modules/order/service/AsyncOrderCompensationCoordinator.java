
package com.example.damai.modules.order.service;

import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.order.mq.OrderRecoveryDecision;
import org.springframework.stereotype.Service;

/**
 * 异步订单补偿协调器。
 *
 * 负责串联：
 * 1. MySQL Guard资格申请
 * 2. Redis幂等库存补偿
 * 3. MySQL Guard补偿完成状态
 * 4. 已创建订单的对账恢复
 *
 * 不开启事务：
 * 必须确保补偿资格事务提交后，
 * 才进行Redis库存补偿。
 */
@Service
public class AsyncOrderCompensationCoordinator {

    public enum Result {
        COMPENSATED,
        ORDER_CREATED,
        NOT_EXPIRED,
        MANUAL_REVIEW
    }

    private final AsyncOrderCompensationClaimService claimService;

    private final AsyncOrderCompensationService compensationService;

    private final OrderDeadLetterRecoveryService recoveryService;

    public AsyncOrderCompensationCoordinator(
            AsyncOrderCompensationClaimService claimService,
            AsyncOrderCompensationService compensationService,
            OrderDeadLetterRecoveryService recoveryService) {

        this.claimService = claimService;
        this.compensationService = compensationService;
        this.recoveryService = recoveryService;
    }

    /**
     * 协调已过演出开始时间的异步请求。
     *
     * 仅供受控恢复流程调用。
     * 不直接暴露成普通用户接口。
     */
    public Result recoverExpiredRequest(
            OrderCreateMessage message,
            String outboxId) {

        // Spring代理方法返回后，资格事务已完成提交
        CompensationClaimResult claim =
                claimService.claimForExpiredEvent(
                        message,
                        outboxId
                );

        return switch (claim) {

            case CLAIMED,
                 ALREADY_CLAIMED,
                 ALREADY_COMPLETED -> {

                // 执行器会再次检查Guard与Redis状态
                compensationService.completeClaimedCompensation(
                        message,
                        outboxId
                );

                yield Result.COMPENSATED;
            }

            case ORDER_CREATED -> {

                // 不允许补偿，转为订单状态恢复
                OrderRecoveryDecision decision =
                        recoveryService.reconcile(
                                message,
                                outboxId
                        );

                if (decision == OrderRecoveryDecision.ALREADY_CREATED
                        || decision == OrderRecoveryDecision.REDIS_REPAIRED) {

                    yield Result.ORDER_CREATED;
                }

                yield Result.MANUAL_REVIEW;
            }

            case NOT_EXPIRED -> Result.NOT_EXPIRED;

            case MANUAL_REVIEW -> Result.MANUAL_REVIEW;
        };
    }
}
