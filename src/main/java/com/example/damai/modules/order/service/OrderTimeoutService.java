package com.example.damai.modules.order.service;

import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.order.mapper.TicketOrderMapper;
import com.example.damai.modules.ticket.mapper.TicketCategoryMapper;
import com.example.damai.modules.ticket.service.StockReleaseTaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 订单超时处理，每次调用处理一笔订单。
 */
@Service
public class OrderTimeoutService {

    private final TicketOrderMapper ticketOrderMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final StockReleaseTaskService stockReleaseTaskService;

    public OrderTimeoutService(
            TicketOrderMapper ticketOrderMapper,
            TicketCategoryMapper ticketCategoryMapper,
            StockReleaseTaskService stockReleaseTaskService) {
        this.ticketOrderMapper = ticketOrderMapper;
        this.ticketCategoryMapper = ticketCategoryMapper;
        this.stockReleaseTaskService = stockReleaseTaskService;
    }

    /**
     * 关闭单个过期订单并释放库存。
     *
     * @param id 订单 ID
     * @param now 本轮扫描时间
     * @return true 表示关闭成功；false 表示订单已不满足关闭条件
     */
    @Transactional
    public boolean closeExpired(Long id, LocalDateTime now) {

        // 条件更新成功，才允许继续释放这笔订单的库存
        int rows = ticketOrderMapper.closeExpired(id, now);

        if (rows != 1) {
            // 可能已被取消、支付或被其他任务关闭，正常跳过
            return false;
        }

        // 当前事务已更新并锁住该订单，读取其票档和购票数量
        TicketOrder order = ticketOrderMapper.selectById(id);

        if (order == null) {
            throw new IllegalStateException("超时关闭后读取订单失败，订单ID：" + id);
        }

        int stockRows = ticketCategoryMapper.releaseStock(order.getTicketCategoryId(), order.getQuantity());

        if (stockRows != 1) {
            // 抛出运行时异常，使订单状态和库存修改一起回滚
            throw new IllegalStateException("超时关闭订单时释放库存失败，订单ID：" + id);
        }


        // MySQL已执行订单超时关闭和库存释放，再记录对应的Redis库存释放任务。
        // 只有本次成功关闭订单的调用能执行到这里，避免重复关闭产生重复任务。
        stockReleaseTaskService.record(order);

        return true;


    }
}