package com.example.damai.modules.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.damai.common.exception.BusinessException;
import com.example.damai.common.response.PageResponse;
import com.example.damai.modules.order.dto.OrderCreateRequest;
import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.order.mapper.AsyncOrderGuardMapper;
import com.example.damai.modules.order.mapper.TicketOrderMapper;
import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.program.entity.EventSession;
import com.example.damai.modules.ticket.entity.TicketCategory;
import com.example.damai.modules.ticket.mapper.TicketCategoryMapper;
import com.example.damai.modules.ticket.redis.TicketPurchaseCache;
import com.example.damai.modules.ticket.redis.TicketPurchaseCache.PurchaseInfo;
import com.example.damai.modules.ticket.service.StockReleaseTaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 购票订单业务层。
 */
@Service
public class TicketOrderService {

    private final TicketOrderMapper ticketOrderMapper;
    private final TicketCategoryMapper ticketCategoryMapper;

    private final TicketPurchaseCache ticketPurchaseCache;

    private final StockReleaseTaskService stockReleaseTaskService;
    private final AsyncOrderGuardMapper asyncOrderGuardMapper;

    public TicketOrderService(
            TicketOrderMapper ticketOrderMapper,
            TicketCategoryMapper ticketCategoryMapper,
            //读取购买资料
            TicketPurchaseCache ticketPurchaseCache,
            StockReleaseTaskService stockReleaseTaskService,
            AsyncOrderGuardMapper asyncOrderGuardMapper
           ) {
        this.ticketOrderMapper = ticketOrderMapper;
        this.ticketCategoryMapper = ticketCategoryMapper;
        this.ticketPurchaseCache = ticketPurchaseCache;
        this.stockReleaseTaskService = stockReleaseTaskService;
        this.asyncOrderGuardMapper = asyncOrderGuardMapper;
    }






    /**
     * 同步下单：在当前事务中锁定 MySQL 库存并创建待支付订单。
     */
    @Transactional
    public Long create(
            Long userId,
            OrderCreateRequest request,
            PurchaseInfo info) {

        if (request == null || info == null
                || !Objects.equals(
                info.ticketCategoryId(),
                request.getTicketCategoryId())) {
            throw new IllegalStateException(
                    "购买资料与下单票档不一致"
            );
        }

        return createInternal(
                userId,
                request.getRequestId(),
                request.getTicketCategoryId(),
                request.getQuantity(),
                info.price(),
                info.startTime()
        );
    }


    /**
     * 异步订单建单事务。
     *
     * 事务包括：
     * 1. 申请Guard建单资格
     * 2. 锁定MySQL库存
     * 3. 创建订单
     * 4. 将Guard改为创建成功
     *
     * 所有MySQL操作必须一起提交或一起回滚。
     */
    @Transactional
    public Long createFromReservation(
            OrderCreateMessage message,
            String outboxId) {

        // 1. 校验消息
        if (message == null
                || message.userId() == null
                || message.userId() <= 0
                || message.requestId() == null
                || !message.requestId().matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        )
                || message.ticketCategoryId() == null
                || message.ticketCategoryId() <= 0
                || message.quantity() == null
                || message.quantity() < 1
                || message.quantity() > 6
                || message.unitPrice() == null
                || message.unitPrice().signum() < 0
                || message.unitPrice().scale() > 2
                || message.unitPrice().compareTo(
                new BigDecimal("9999999999.99")) > 0
                || message.eventStartTime() == null
                || message.acceptedAt() == null
                || !message.acceptedAt()
                .isBefore(message.eventStartTime())
                || outboxId == null
                || outboxId.length() > 64
                || !outboxId.matches("^[0-9]+-[0-9]+$")) {

            throw new BusinessException(
                    "INVALID_PARAM",
                    "异步订单参数不合法"
            );
        }

        // 2. 提前拒绝已经开始的演出
        LocalDateTime now =
                LocalDateTime.now(ZoneId.of("Asia/Shanghai"));

        if (!now.isBefore(message.eventStartTime())) {
            throw new BusinessException(
                    "SALE_ENDED",
                    "演出已经开始"
            );
        }

        // 3. 在当前MySQL事务内竞争处理资格
        int claimed = asyncOrderGuardMapper.claimCreate(
                message.userId(),
                message.requestId(),
                message.ticketCategoryId(),
                message.quantity(),
                outboxId,
                message.unitPrice(),
                message.eventStartTime()
        );

        if (claimed != 1) {

            // 可能是另一建单事务已完成，
            // 也可能是补偿任务已取得资格。
            //
            // 这里不能直接补偿，也不确认消息处理成功。
            throw new IllegalStateException(
                    "异步订单Guard资格冲突，requestId="
                            + message.requestId()
            );
        }

        // 4. 复用已有MySQL建单逻辑
        //
        // 这里不要调用this.create()。
        // 必须使用之前提取的createInternal()，
        // 使全部操作处于同一个事务。
        Long orderId = createInternal(
                message.userId(),
                message.requestId(),
                message.ticketCategoryId(),
                message.quantity(),
                message.unitPrice(),
                message.eventStartTime()
        );

        // 5. 在同一事务内记录建单结果
        int updated = asyncOrderGuardMapper.finishCreate(
                message.userId(),
                message.requestId(),
                outboxId,
                orderId
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Guard建单状态更新失败，requestId="
                            + message.requestId()
            );
        }

        // Guard、库存和订单交给当前事务统一提交，调用方再确认 Redis 建单结果。
        return orderId;
    }


    /**
     * 同步和异步建单共用的MySQL事务内部实现。
     *
     * 只能由本类带有@Transactional的公开方法调用。
     */
    private Long createInternal(
            Long userId,
            String requestId,
            Long ticketCategoryId,
            int quantity,
            BigDecimal unitPrice,
            LocalDateTime eventStartTime) {

        // 提前拒绝已开始的演出，减少无意义行锁竞争
        LocalDateTime now =
                LocalDateTime.now(ZoneId.of("Asia/Shanghai"));

        if (!now.isBefore(eventStartTime)) {
            throw new BusinessException(
                    "SALE_ENDED",
                    "演出已经开始，无法创建待支付订单"
            );
        }

        // MySQL库存锁定：
        // available_stock -= quantity
        // locked_stock += quantity
        int rows = ticketCategoryMapper.lockStock(
                ticketCategoryId, quantity
        );

        if (rows != 1) {
            throw new BusinessException(
                    "INSUFFICIENT_STOCK",
                    "MySQL可售库存不足"
            );
        }

        // 可能等待MySQL行锁，获得锁后必须重新检查时间
        now = LocalDateTime.now(
                ZoneId.of("Asia/Shanghai")
        );

        if (!now.isBefore(eventStartTime)) {
            // 抛出运行时异常，MySQL事务回滚前面的库存更新
            throw new BusinessException(
                    "SALE_ENDED",
                    "等待库存锁期间演出已经开始"
            );
        }

        TicketOrder order = new TicketOrder();

        order.setUserId(userId);
        order.setRequestId(requestId);
        order.setTicketCategoryId(ticketCategoryId);
        order.setQuantity(quantity);

        // 核心：同步使用服务端PurchaseInfo价格；
        // 异步使用受理时的消息价格快照。
        order.setUnitPrice(unitPrice);
        order.setTotalAmount(
                unitPrice.multiply(BigDecimal.valueOf(quantity))
        );

        // 0 = 待支付
        order.setStatus(0);

        // 支付有效期最多15分钟，且不能超过演出开始时间
        LocalDateTime expireTime = now.plusMinutes(15);

        if (expireTime.isAfter(eventStartTime)) {
            expireTime = eventStartTime;
        }

        order.setExpireTime(expireTime);

        if (ticketOrderMapper.insert(order) != 1) {
            throw new IllegalStateException(
                    "MySQL创建订单失败"
            );
        }

        return order.getId();
    }

    /**
     * 查询当前用户的订单详情。
     *
     * @param userId 当前用户 ID
     * @param id 订单 ID
     * @return 当前用户的订单
     */
    public TicketOrder getById(Long userId, Long id) {
        if (id <= 0) {
            throw new BusinessException(
                    "INVALID_PARAM", "订单ID必须大于0");
        }
        LambdaQueryWrapper<TicketOrder> query = new LambdaQueryWrapper<>();

        // 同时限制所属用户，避免查询到其他用户的订单
        query.eq(TicketOrder::getId, id)
                .eq(TicketOrder::getUserId, userId);

        TicketOrder order = ticketOrderMapper.selectOne(query);

        // 不存在或不属于当前用户，统一提示订单不存在
        if (order == null) {
            throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        }

        return order;
    }

    /**
     * 取消当前用户的待支付订单，同时释放库存。
     */
    @Transactional
    public boolean cancel(Long userId, Long id) {
        if (id <= 0) {
            throw new BusinessException(
                    "INVALID_PARAM", "订单ID必须大于0");
        }
        // 复用已有查询，检查订单是否存在且属于当前用户
        TicketOrder order = getById(userId, id);

        // 通过条件更新竞争取消资格，不能只依赖查询时的订单状态.防止后续重复释放库存
        int rows = ticketOrderMapper.cancelPending(id, userId);

        if (rows != 1) {
            throw new BusinessException(
                    "ORDER_STATUS_CONFLICT",
                    "订单当前状态不允许取消"
            );
        }

        int stockRows = ticketCategoryMapper.releaseStock(
                order.getTicketCategoryId(),
                order.getQuantity()
        );

        if (stockRows != 1) {
            // 库存异常时必须回滚订单取消，并由兜底处理器记录错误
            throw new IllegalStateException("取消订单时释放库存失败");
        }

         // MySQL已执行订单取消和库存释放，再记录这笔订单的Redis库存释放任务。
        // 三项修改共用当前MySQL事务，任务写入失败时，订单取消和库存释放也一起回滚。
        stockReleaseTaskService.record(order);


        return true;
    }

    /**
     * 模拟支付：将待支付订单改为已支付，并将锁定库存转为已售。
     * 仅用于本地学习测试，不涉及真实扣款。
     *
     * @param userId 当前用户 ID
     * @param id 订单 ID
     * @return 是否支付成功
     */
    @Transactional
    public boolean mockPay(Long userId, Long id) {
        if (id <= 0) {
            throw new BusinessException("INVALID_PARAM", "订单ID必须大于0");
        }

        // 检查订单存在且属于当前用户，获取票档和购票数量
        TicketOrder order = getById(userId, id);

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));

        // SQL 同时检查：所属用户、待支付状态、未超过支付截止时间
        int rows = ticketOrderMapper.payPending(id, userId, now);

        if (rows != 1) {
            throw new BusinessException("ORDER_PAYMENT_CONFLICT", "订单已过期或当前状态不允许支付");
        }

        // 下单时已扣减可售库存，此处只将锁定库存转为已售
        int stockRows = ticketCategoryMapper.confirmSold(
                order.getTicketCategoryId(),
                order.getQuantity()
        );

        if (stockRows != 1) {
            // 库存转换失败时，订单支付状态也必须回滚
            throw new IllegalStateException("支付订单时确认库存失败，订单ID：" + id);

        }

        return true;
    }

    /**
     * 分页查询当前用户的订单。
     *
     * @param userId 当前登录用户 ID，由后端提供
     * @param status 订单状态；不传时查询全部状态
     * @param page 页码，从 1 开始
     * @param size 每页条数
     */
    public PageResponse<TicketOrder> listMyOrders(Long userId, Integer status, long page, long size) {

        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException("INVALID_PAGE_PARAM", "页码必须大于等于1，每页条数必须在1到100之间");
        }

        // 订单状态：0待支付、1已支付、2已取消、3已超时
        if (status != null && (status < 0 || status > 3)) {
            throw new BusinessException(
                    "INVALID_PARAM", "订单状态只能为0、1、2或3");
        }

        LambdaQueryWrapper<TicketOrder> query = new LambdaQueryWrapper<>();

        // 必须限定所属用户，不能让客户端自行指定查询其他人的订单
        query.eq(TicketOrder::getUserId, userId);

        if (status != null) {
            query.eq(TicketOrder::getStatus, status);
        }

        // 新订单优先，使用唯一 ID 保证排序明确
        query.orderByDesc(TicketOrder::getId);

        Page<TicketOrder> result = ticketOrderMapper.selectPage(
                new Page<>(page, size), query);

        return new PageResponse<>(
                result.getRecords(),
                result.getTotal(),
                result.getCurrent(),
                result.getSize(),
                result.getPages()
        );
    }

    /**
     * 根据请求标识查询当前用户的订单。
     *
     * @return 已存在的订单；暂未查询到时返回 null
     */
    public TicketOrder findByRequestId(Long userId, String requestId) {

        if (!requestId.matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw new BusinessException(
                    "INVALID_PARAM", "请求标识必须为小写UUID格式");
        }
        LambdaQueryWrapper<TicketOrder> query = new LambdaQueryWrapper<>();

        // 只能查询当前登录用户的请求，不能指定其他用户ID
        query.eq(TicketOrder::getUserId, userId).eq(TicketOrder::getRequestId, requestId);

        return ticketOrderMapper.selectOne(query);
    }








    /**
     * 本次校验得到的数据，供创建订单继续使用。
     * 只是数据载体，不是新的Spring组件，也不是Redis缓存。
     */
    public record PurchaseContext(
            TicketCategory category,
            EventSession session
    ) {
    }
    /**
     * 读取购买资料并检查是否允许购买。
     *
     * 在数据库创建事务之前调用。
     * 缓存命中时，无需查询票档、场次、演出三张表。
     */
    public PurchaseInfo validatePurchase(OrderCreateRequest request) {

        // 缓存缺失时，TicketPurchaseCache内部负责加载及并发保护
        PurchaseInfo info = ticketPurchaseCache.get(request.getTicketCategoryId());

        // 演出必须已经上架
        if (!Integer.valueOf(1).equals(info.eventStatus())) {
            throw new BusinessException("EVENT_NOT_ON_SALE", "演出未上架");
        }

        // 场次必须已经启售
        if (!Integer.valueOf(1).equals(info.sessionStatus())) {
            throw new BusinessException("SESSION_NOT_ON_SALE", "场次未启售或已停售");
        }

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));

        /*
         * 时间资料可以缓存，但“现在是否允许购买”
         * 必须使用每次请求的当前时间重新判断。
         */
        if (now.isBefore(info.saleStartTime())) {
            throw new BusinessException("SALE_NOT_STARTED", "尚未到开售时间");
        }

        if (!now.isBefore(info.startTime())) {
            throw new BusinessException("SALE_ENDED", "演出已开始，停止售票");
        }

        // 将已经读取、校验的资料交给后续创建方法使用
        return info;
    }
}