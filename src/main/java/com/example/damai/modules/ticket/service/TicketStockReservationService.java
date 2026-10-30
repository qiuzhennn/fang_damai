package com.example.damai.modules.ticket.service;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.ticket.redis.StockReservationResult;
import com.example.damai.modules.ticket.redis.TicketRedisKeys;
import com.example.damai.modules.ticket.redis.TicketStockScriptExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 封装Redis预扣调用、Key生成和返回码转换。
 * 目前不负责创建数据库订单。
 */
@Service
public class TicketStockReservationService {

    private final TicketStockScriptExecutor executor;
    private final StringRedisTemplate redisTemplate;

    public TicketStockReservationService(
            TicketStockScriptExecutor executor,StringRedisTemplate redisTemplate) {
        this.executor = executor;
        this.redisTemplate = redisTemplate;
    }

    public StockReservationResult reserve(
            Long userId,
            String requestId,
            Long ticketCategoryId,
            Integer quantity) {

        validate(userId, requestId, ticketCategoryId, quantity);

        //该票档剩余的可预扣数量的 key : stockKey：fang-damai:ticket:stock:ticketCategoryId
        String stockKey = TicketRedisKeys.stock(ticketCategoryId);
        //当前用户这次购买的参数和处理状态的 key ：requestKey：fang-damai:ticket:request:userId:某个UUID
        String requestKey = TicketRedisKeys.request(userId, requestId);

        //调用 Lua，尝试占票
        Long code = executor.reserve(stockKey, requestKey, ticketCategoryId, quantity);

        if (code == null) {
            throw new IllegalStateException("Redis预扣未返回结果");
        }

        // 当前调用已经成功占住所需票数，把这个资格交给 submit()，由它继续创建订单。
        if (code == 0L) {
            return StockReservationResult.RESERVED_NOW;
        }
        // 这次购买已被受理，不能再次扣库存，也不能直接再创建订单，应先确认原来的处理结果。
        if (code == 5L) {
            return StockReservationResult.RESERVED_BEFORE;
        }
        // Redis 记录显示同一次购买已有建单成功记录，后续恢复原订单，避免用户重试时再次占票、再次建单。
        if (code == 7L) {
            return StockReservationResult.ORDER_CREATED;
        }
        // 这次购买之前预扣过库存，但由于建单失败（已超时，数据库出现错误等等），预扣的库存已经补偿回来了，不能用原请求重新预扣
        if (code == 8L) {
            return StockReservationResult.COMPENSATED;
        }
        // 库存 Key 不存在，当前不能受理预扣
        if (code == 1L) {
            throw new BusinessException("STOCK_NOT_INITIALIZED", "库存尚未初始化或已不可用");
        }
        //剩余库存不足以满足购买数量
        if (code == 2L) {
            throw new BusinessException("INSUFFICIENT_STOCK", "可售库存不足");
        }
        //脚本发现预扣参数不合法
        if (code == 3L) {
            throw new BusinessException("INVALID_PARAM", "预扣参数不合法");
        }
        //同一请求标识被用于不同票档或数量
        if (code == 6L) {
            throw new BusinessException("REQUEST_PARAM_CONFLICT", "同一请求标识不能更改票档或购买数量");
        }

        // 库存或请求记录损坏属于内部异常，不能当作普通售罄
        throw new IllegalStateException("Redis预扣异常，返回码：" + code);
    }


    /**
     * 异步建单预扣入口。
     *
     * 仅负责：
     * 1. 参数校验
     * 2. Redis Key 生成
     * 3. 执行 Lua 原子预扣与任务登记
     * 4. 转换 Lua 返回结果
     *
     * 不负责创建 MySQL 订单。
     */
    public StockReservationResult reserveAsync(OrderCreateMessage message) {

        if (message == null) {
            throw new BusinessException(
                    "INVALID_PARAM", "建单消息不能为空"
            );
        }

        // 复用现有用户、requestId、票档、数量校验
        validate(
                message.userId(),
                message.requestId(),
                message.ticketCategoryId(),
                message.quantity()
        );

        // 校验服务端购买资料快照
        if (message.unitPrice() == null
                || message.unitPrice().signum() < 0
                || message.eventStartTime() == null
                || message.acceptedAt() == null
                || !message.acceptedAt()
                .isBefore(message.eventStartTime())) {

            throw new BusinessException(
                    "INVALID_PARAM", "建单资料不合法"
            );
        }

        // 票档库存 Key
        String stockKey = TicketRedisKeys.stock(
                message.ticketCategoryId()
        );

        // 本次购买请求 Key
        String requestKey = TicketRedisKeys.request(
                message.userId(),
                message.requestId()
        );

        // 可靠待投递任务 Stream
        String streamKey =
                TicketRedisKeys.orderCreateOutboxStream();

        // 执行 Redis Lua
        Long code = executor.reserveAsync(
                stockKey,
                requestKey,
                streamKey,
                message
        );

        if (code == null) {
            throw new IllegalStateException(
                    "Redis异步预扣未返回结果"
            );
        }

        // 本次成功获得库存预扣资格
        if (code == 0L) {
            return StockReservationResult.RESERVED_NOW;
        }

        // 同一请求已经预扣，不允许重复登记任务
        if (code == 5L) {
            return StockReservationResult.RESERVED_BEFORE;
        }

        // 请求对应订单已创建
        if (code == 7L) {
            return StockReservationResult.ORDER_CREATED;
        }

        // 请求此前已补偿
        if (code == 8L) {
            return StockReservationResult.COMPENSATED;
        }

        // 库存尚未初始化
        if (code == 1L) {
            throw new BusinessException(
                    "STOCK_NOT_INITIALIZED",
                    "库存尚未初始化或已不可用"
            );
        }

        // 库存不足
        if (code == 2L) {
            throw new BusinessException(
                    "INSUFFICIENT_STOCK",
                    "可售库存不足"
            );
        }

        // 参数错误
        if (code == 3L) {
            throw new BusinessException(
                    "INVALID_PARAM",
                    "异步预扣参数不合法"
            );
        }

        // 同一请求标识更改了票档或数量
        if (code == 6L) {
            throw new BusinessException(
                    "REQUEST_PARAM_CONFLICT",
                    "同一请求标识不能更改票档或数量"
            );
        }

        // 同步与异步模式冲突
        if (code == 11L) {
            throw new BusinessException(
                    "REQUEST_MODE_CONFLICT",
                    "同一请求不能在同步和异步模式之间切换"
            );
        }

        // Redis Stream 写入失败
        if (code == 10L) {
            throw new IllegalStateException(
                    "Redis异步建单任务登记失败"
            );
        }

        // 其他内部错误，例如库存数据损坏
        throw new IllegalStateException(
                "Redis异步预扣异常，返回码：" + code
        );
    }

    private void validate(
            Long userId,
            String requestId,
            Long ticketCategoryId,
            Integer quantity) {

        if (userId == null || userId <= 0
                || ticketCategoryId == null || ticketCategoryId <= 0
                || quantity == null || quantity < 1 || quantity > 6) {
            throw new BusinessException(
                    "INVALID_PARAM", "用户、票档或购买数量不合法");
        }

        if (requestId == null || !requestId.matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw new BusinessException(
                    "INVALID_PARAM", "请求标识必须为小写UUID格式");
        }
    }

    /**
     * 读取同一次购买的Redis请求状态。
     *
     * 返回null：当前没有请求记录。
     * 此方法只读取，不创建记录，也不扣减库存。
     */
    public StockReservationResult readRequestState(
            Long userId,
            String requestId,
            Long ticketCategoryId,
            Integer quantity) {

        // 进行参数校验
        validate(userId, requestId, ticketCategoryId, quantity);

        String requestKey = TicketRedisKeys.request(userId, requestId);

        // 一次读取请求记录中的全部字段
        Map<Object, Object> record = redisTemplate.opsForHash().entries(requestKey);

        if (record.isEmpty()) {
            return null;
        }

        Object oldCategoryId = record.get("ticketCategoryId");
        Object oldQuantity = record.get("quantity");
        Object status = record.get("status");

        // 有记录但缺少必要字段，属于数据异常
        if (oldCategoryId == null
                || oldQuantity == null
                || status == null) {
            throw new IllegalStateException("Redis请求记录不完整");
        }

        // 同一个请求标识，必须始终对应相同的购买参数
        if (!ticketCategoryId.toString().equals(oldCategoryId.toString())
                || !quantity.toString().equals(oldQuantity.toString())) {

            throw new BusinessException(
                    "REQUEST_PARAM_CONFLICT",
                    "同一请求标识不能更改票档或购买数量"
            );
        }

        // 使用已有枚举，向协调层表达处理状态
        return switch (status.toString()) {
            case "RESERVED" ->
                    StockReservationResult.RESERVED_BEFORE;

            case "ORDER_CREATED" ->
                    StockReservationResult.ORDER_CREATED;

            case "COMPENSATED" ->
                    StockReservationResult.COMPENSATED;

            default ->
                    throw new IllegalStateException("Redis请求状态异常");
        };
    }

    /**
     * MySQL已确认建单成功，将订单ID和成功状态写入Redis请求记录。
     */
    public void markOrderCreated(
            Long userId,
            String requestId,
            Long ticketCategoryId,
            Integer quantity,
            Long orderId) {

        validate(userId, requestId, ticketCategoryId, quantity);

        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("回写Redis的订单ID不合法");
        }

        String requestKey = TicketRedisKeys.request(userId, requestId);

        Long code = executor.markOrderCreated(
                requestKey,
                ticketCategoryId,
                quantity,
                orderId
        );

        // Lua返回0，表示本次回写成功，或此前已回写同一订单。
        if (code == null || code != 0L) {
            throw new IllegalStateException(
                    "Redis建单结果回写未成功，返回码：" + code
            );
        }
    }

    /**
     * 当前MySQL创建事务已确认回滚，没有对应成功订单，
     * 且同一请求没有仍在执行的创建任务时，退回Redis预扣库存。
     */
    public void compensate(
            Long userId,
            String requestId,
            Long ticketCategoryId,
            Integer quantity) {

        validate(userId, requestId, ticketCategoryId, quantity);

        String stockKey = TicketRedisKeys.stock(ticketCategoryId);
        String requestKey = TicketRedisKeys.request(userId, requestId);

        Long code = executor.compensate(
                stockKey,
                requestKey,
                ticketCategoryId,
                quantity
        );

        // 返回0表示本次退回成功，返回5表示此前已经退回。
        // 两种情况都确认补偿完成，同一次预扣不会重复增加库存。
        if (code == null || (code != 0L && code != 5L)) {
            throw new IllegalStateException(
                    "Redis库存补偿未确认完成，返回码：" + code
            );
        }
    }

    /**
     * 查询原有异步购买请求。
     *
     * 返回null：尚未受理，可以申请新的预扣。
     * 返回状态：已经受理，不重复扣库存。
     *
     * 不允许拿同步请求ID进入异步流程。
     */
    public StockReservationResult readAsyncRequestState(
            Long userId,
            String requestId,
            Long ticketCategoryId,
            Integer quantity) {

        StockReservationResult previous =
                readRequestState(
                        userId,
                        requestId,
                        ticketCategoryId,
                        quantity
                );

        if (previous == null) {
            return null;
        }

        String requestKey =
                TicketRedisKeys.request(userId, requestId);

        Object mode = redisTemplate.opsForHash()
                .get(requestKey, "mode");

        if (!"ASYNC".equals(mode)) {
            throw new BusinessException(
                    "REQUEST_MODE_CONFLICT",
                    "同一请求不能在同步和异步模式之间切换"
            );
        }

        return previous;
    }
}