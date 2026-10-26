package com.example.damai.modules.ticket.service;

import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.ticket.mapper.StockReleaseTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在关单事务内记录Redis库存释放任务。
 *
 * 订单关闭、MySQL库存释放、任务记录共同提交或回滚。
 * 提交成功后，由StockReleaseTaskJob扫描任务并调用StockReleaseTaskExecutor回补Redis。
 */
@Service
public class StockReleaseTaskService {

    private final StockReleaseTaskMapper taskMapper;

    public StockReleaseTaskService(StockReleaseTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 调用方必须已在当前事务中成功关闭订单、释放MySQL库存。
     *
     * 只在当前事务中写入任务；任务插入失败时，关单与MySQL库存释放也一起回滚。
     * 本方法返回后，由调用方事务继续提交，后台扫描器处理已提交的任务。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(TicketOrder order) {
        // 用原订单的用户和请求标识定位Redis预扣记录，确保释放的是同一次购买。
        if (order.getRequestId() == null || order.getRequestId().isBlank()) {
            throw new IllegalStateException("Redis订单缺少请求标识，订单ID：" + order.getId());
        }

        // 任务保存原订单的占票资料，后台按此快照执行幂等回补，不接受客户端指定。
        int rows = taskMapper.insertTask(
                order.getId(),
                order.getUserId(),
                order.getRequestId(),
                order.getTicketCategoryId(),
                order.getQuantity()
        );

        if (rows != 1) {
            throw new IllegalStateException("记录库存释放任务失败，订单ID：" + order.getId());
        }
    }
}
