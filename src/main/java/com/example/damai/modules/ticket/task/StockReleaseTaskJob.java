package com.example.damai.modules.ticket.task;

import com.example.damai.modules.ticket.mapper.StockReleaseTaskMapper;
import com.example.damai.modules.ticket.service.StockReleaseTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StockReleaseTaskJob {

    private static final Logger log =
            LoggerFactory.getLogger(StockReleaseTaskJob.class);

    private final StockReleaseTaskMapper taskMapper;
    private final StockReleaseTaskExecutor taskExecutor;

    public StockReleaseTaskJob(
            StockReleaseTaskMapper taskMapper,
            StockReleaseTaskExecutor taskExecutor) {

        this.taskMapper = taskMapper;
        this.taskExecutor = taskExecutor;
    }

    @Scheduled(initialDelay = 10000, fixedDelay = 5000)
    public void scanPendingTasks() {

        for (Long taskId : taskMapper.selectPendingIds(100)) {
            try {
                taskExecutor.execute(taskId);

            } catch (RuntimeException exception) {
                // 单条任务执行异常时，继续处理本批其他任务。
                log.error(
                        "扫描执行库存释放任务异常，任务ID：{}",
                        taskId, exception);
            }
        }
    }
}