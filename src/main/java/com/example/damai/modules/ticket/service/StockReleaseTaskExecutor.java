package com.example.damai.modules.ticket.service;

import com.example.damai.modules.ticket.entity.StockReleaseTask;
import com.example.damai.modules.ticket.mapper.StockReleaseTaskMapper;
import com.example.damai.modules.ticket.redis.TicketRedisKeys;
import com.example.damai.modules.ticket.redis.TicketStockScriptExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class StockReleaseTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(StockReleaseTaskExecutor.class);

    private static final Map<Long, String> REVIEW_REASONS = Map.of(
            1L, "Redis预扣请求记录不存在，无法确认本次占票",
            2L, "Redis记录与MySQL释放任务的票档、数量或订单ID不一致",
            3L, "MySQL已有关闭的订单，但Redis记录为建单失败已补偿",
            4L, "Redis库存或预扣请求记录的数据、状态异常",
            6L, "Redis库存Key不存在，不能按订单数量直接重建库存"
    );

    private final StockReleaseTaskMapper taskMapper;
    private final TicketStockScriptExecutor executor;



    public StockReleaseTaskExecutor(
            StockReleaseTaskMapper taskMapper,
            TicketStockScriptExecutor executor,
            @Qualifier("ticketStockReleaseClosedScript")
            DefaultRedisScript<Long> releaseClosedScript) {

        this.taskMapper = taskMapper;
        this.executor = executor;

    }

    /**
     * 执行一条已提交关单事务留下的Redis库存释放任务。
     * 立即执行和后台扫描都调用这里。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(Long taskId) {

        // 锁住任务，避免多个执行者同时修改同一条任务的处理结果。
        StockReleaseTask task = taskMapper.selectPendingForUpdate(taskId);

        if (task == null) {
            // 任务可能已完成、已进入核对，或尚未到重试时间。
            return;
        }

        Long code;

        try {
            // 票档、数量和购买请求都取自MySQL任务快照，
            // 不接受客户端指定需要退回哪一笔库存。
            code = executor.releaseClosedOrder(
                    TicketRedisKeys.stock(task.ticketCategoryId()),
                    TicketRedisKeys.request(
                            task.userId(), task.requestId()),
                    task.ticketCategoryId(),
                    task.quantity(),
                    task.orderId()
            );

            if (code == null) {
                throw new IllegalStateException(
                        "Redis未返回库存释放结果");
            }

        } catch (RuntimeException exception) {

            // Redis可能没有执行，也可能已执行但响应丢失。
            // 保留任务，后续依靠幂等脚本重试，不能直接认定释放失败。
            checkUpdated(
                    taskMapper.retryLater(
                            taskId,
                            "Redis回补失败或结果未确认："
                                    + exception.getClass().getSimpleName()),
                    taskId
            );

            log.warn(
                    "Redis库存释放结果未确认，任务ID：{}，订单ID：{}",
                    taskId, task.orderId(), exception);

            return;
        }

        if (code == 0L || code == 5L) {

            // 本次已释放，或者Redis确认此前已经释放，
            // 都可以将MySQL任务标记为完成。
            checkUpdated(taskMapper.markDone(taskId), taskId);
            return;
        }

        // 记录缺失或资料冲突时，继续自动加库存可能造成超卖。
        // 保存具体原因，停止自动重试，等待核对。
        String reason = REVIEW_REASONS.getOrDefault(
                code, "Redis库存释放返回未知结果：" + code);

        checkUpdated(
                taskMapper.markReview(taskId, reason),
                taskId
        );

        log.error(
                "库存释放任务需要核对，任务ID：{}，订单ID：{}，原因：{}",
                taskId, task.orderId(), reason);
    }

    private void checkUpdated(int rows, Long taskId) {
        if (rows != 1) {
            throw new IllegalStateException(
                    "更新库存释放任务失败，任务ID：" + taskId);
        }
    }


}