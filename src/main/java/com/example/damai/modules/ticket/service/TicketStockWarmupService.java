package com.example.damai.modules.ticket.service;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.program.entity.EventSession;
import com.example.damai.modules.program.mapper.EventSessionMapper;
import com.example.damai.modules.ticket.entity.TicketCategory;
import com.example.damai.modules.ticket.mapper.StockReleaseTaskMapper;
import com.example.damai.modules.ticket.mapper.TicketCategoryMapper;
import com.example.damai.modules.ticket.redis.TicketRedisKeys;
import com.example.damai.modules.ticket.redis.TicketStockScriptExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员按票档初始化、撤销Redis库存预热。
 */
@Service
public class TicketStockWarmupService {

    private static final Logger log =
            LoggerFactory.getLogger(TicketStockWarmupService.class);

    private final TicketCategoryMapper categoryMapper;
    private final EventSessionMapper sessionMapper;
    private final StockReleaseTaskMapper taskMapper;
    private final StringRedisTemplate redisTemplate;
    private final TicketStockScriptExecutor executor;

    public TicketStockWarmupService(
            TicketCategoryMapper categoryMapper,
            EventSessionMapper sessionMapper,
            StockReleaseTaskMapper taskMapper,
            StringRedisTemplate redisTemplate,
            TicketStockScriptExecutor executor) {

        this.categoryMapper = categoryMapper;
        this.sessionMapper = sessionMapper;
        this.taskMapper = taskMapper;
        this.redisTemplate = redisTemplate;
        this.executor = executor;
    }

    /**
     * 撤销库存预热。
     * 只有没有尚待处理的占票、回补时，才允许删除Redis库存。
     */
    @Transactional
    public boolean cancelWarmup(Long ticketCategoryId) {

        TicketCategory category =
                requireMaintenanceAllowed(ticketCategoryId);

        Long code = executor.cancelWarmup(
                TicketRedisKeys.stock(ticketCategoryId),
                category.getAvailableStock()
        );

        if (code != null && (code == 0L || code == 1L)) {
            // 已删除或原本就不存在，都确认当前处于未预热状态。
            log.info("票档库存预热已撤销，票档ID：{}", ticketCategoryId);
            return true;
        }

        if (code != null && code == 2L) {
            // Redis先预扣、MySQL后建单，两边数量不一致时，
            // 即使MySQL没有锁定库存，也可能仍有购买正在处理。
            throw new BusinessException(
                    "STOCK_WARMUP_CONFLICT",
                    "Redis库存与MySQL可售库存不一致，请先核对预扣和回补结果"
            );
        }

        throw new IllegalStateException(
                "撤销库存预热结果异常，返回码：" + code);
    }

    /**
     * 将MySQL当前可售库存初始化到Redis，已有Key不覆盖。
     */
    @Transactional
    public boolean warmup(Long ticketCategoryId) {

        TicketCategory category =
                requireMaintenanceAllowed(ticketCategoryId);

        Boolean initialized = redisTemplate.opsForValue().setIfAbsent(
                TicketRedisKeys.stock(ticketCategoryId),
                category.getAvailableStock().toString()
        );

        if (Boolean.FALSE.equals(initialized)) {
            throw new BusinessException(
                    "STOCK_ALREADY_INITIALIZED",
                    "Redis库存已存在，不能重复预热覆盖");
        }

        if (!Boolean.TRUE.equals(initialized)) {
            throw new IllegalStateException("库存预热结果未确认");
        }

        log.info(
                "票档库存预热成功，票档ID：{}，初始可售库存：{}",
                ticketCategoryId, category.getAvailableStock());

        return true;
    }

    /**
     * 检查当前票档是否允许维护库存初始化状态。
     * 调用方事务持有票档和场次行锁，直到Redis操作结束。
     */
    private TicketCategory requireMaintenanceAllowed(Long id) {

        if (id == null || id <= 0) {
            throw new BusinessException(
                    "INVALID_PARAM", "票档ID必须大于0");
        }

        TicketCategory category = categoryMapper.selectByIdForUpdate(id);

        if (category == null) {
            throw new BusinessException(
                    "TICKET_CATEGORY_NOT_FOUND", "票档不存在");
        }

        EventSession session =
                sessionMapper.selectByIdForUpdate(category.getSessionId());

        if (session == null) {
            throw new BusinessException(
                    "SESSION_NOT_FOUND", "所属场次不存在");
        }

        // 库存维护期间不允许场次切换销售状态。
        if (!Integer.valueOf(0).equals(session.getStatus())
                && !Integer.valueOf(2).equals(session.getStatus())) {
            throw new BusinessException(
                    "STOCK_WARMUP_CONFLICT",
                    "请先将场次设为待开售或停售");
        }

        // 待支付订单仍占用库存，必须先通过正常业务处理。
        if (!Integer.valueOf(0).equals(category.getLockedStock())) {
            throw new BusinessException(
                    "STOCK_WARMUP_CONFLICT",
                    "票档仍有锁定库存，请先处理待支付订单");
        }

        // MySQL关单后，Redis可能尚未回补；待核对任务也不能跳过。
        if (taskMapper.existsUnfinishedByCategoryId(id)) {
            throw new BusinessException(
                    "STOCK_WARMUP_CONFLICT",
                    "票档仍有未完成或待核对的库存释放任务");
        }

        Integer available = category.getAvailableStock();

        if (available == null || available < 0) {
            throw new IllegalStateException("MySQL可售库存数据异常");
        }

        return category;
    }
}