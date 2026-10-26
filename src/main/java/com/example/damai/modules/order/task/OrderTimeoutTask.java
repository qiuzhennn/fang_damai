package com.example.damai.modules.order.task;

import com.example.damai.modules.order.mapper.TicketOrderMapper;
import com.example.damai.modules.order.service.OrderTimeoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 定期扫描过期订单。
 * 扫描任务不加事务，单笔订单由 Service 独立开启事务。
 */
@Component
public class OrderTimeoutTask {

    private static final Logger log =
            LoggerFactory.getLogger(OrderTimeoutTask.class);

    private final TicketOrderMapper ticketOrderMapper;
    private final OrderTimeoutService orderTimeoutService;

    public OrderTimeoutTask(
            TicketOrderMapper ticketOrderMapper,
            OrderTimeoutService orderTimeoutService) {
        this.ticketOrderMapper = ticketOrderMapper;
        this.orderTimeoutService = orderTimeoutService;
    }

    /**
     * 启动后等待 10 秒执行第一轮，
     * 每轮执行结束后，再等待 10 秒执行下一轮。
     */
    @Scheduled(initialDelay = 10_000, fixedDelay = 10_000)
    public void closeExpiredOrders() {
        LocalDateTime now =
                LocalDateTime.now(ZoneId.of("Asia/Shanghai"));

        // 每轮最多处理 100 笔，避免一次加载过多订单
        List<Long> ids = ticketOrderMapper.selectExpiredIds(now, 100);

        for (Long id : ids) {
            try {
                // 调用另一个 Spring Bean，确保事务代理生效
                if (orderTimeoutService.closeExpired(id, now)) {
                    log.info("超时订单已关闭，订单ID：{}", id);
                }
            } catch (Exception exception) {
                // 单笔失败记录日志，继续处理本批其他订单
                log.error("超时订单处理失败，订单ID：{}", id, exception);
            }
        }
    }
}