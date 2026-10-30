package com.example.damai.modules.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.order.dto.OrderCreateRequest;
import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.order.mapper.TicketOrderMapper;
import com.example.damai.modules.order.mq.OrderCreateMessage;
import com.example.damai.modules.order.ratelimit.PurchaseRateLimiter;
import com.example.damai.modules.ticket.redis.StockReservationResult;
import com.example.damai.modules.ticket.redis.TicketPurchaseCache.PurchaseInfo;
import com.example.damai.modules.ticket.service.TicketStockReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.damai.modules.order.ratelimit.OrderAdmissionGuard;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import com.example.damai.modules.order.ratelimit.OrderBacklogGuard;

/**
 * 下单入口：识别重复请求，复用已有订单。
 *
 */
@Service
public class OrderSubmissionService {
    //订单mapper
    private final TicketOrderMapper orderMapper;
    //订单服务
    private final TicketOrderService orderService;

    private final TicketStockReservationService reservationService;

    private final PurchaseRateLimiter purchaseRateLimiter;
    private final OrderAdmissionGuard orderAdmissionGuard;
    private final OrderBacklogGuard orderBacklogGuard;


    private static final Logger log =
            LoggerFactory.getLogger(OrderSubmissionService.class);

    public OrderSubmissionService(
            TicketOrderMapper orderMapper,
            TicketOrderService orderService,
            TicketStockReservationService reservationService,
            PurchaseRateLimiter purchaseRateLimiter,
            OrderAdmissionGuard orderAdmissionGuard,
            OrderBacklogGuard orderBacklogGuard) {

        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.reservationService = reservationService;
        this.purchaseRateLimiter = purchaseRateLimiter;
        this.orderAdmissionGuard = orderAdmissionGuard;
        this.orderBacklogGuard = orderBacklogGuard;

    }




    /**
     * 协调层不运行在事务中。
     * 真正的库存与订单写入，由 TicketOrderService 开启事务。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Long submit(Long userId, OrderCreateRequest request) {

        if (userId == null || userId <= 0 || request == null) {
            throw new BusinessException("INVALID_PARAM", "下单参数不合法");
        }

        // 1. 读取 Redis 请求状态,先识别同一次购买是否已经处理过
        StockReservationResult previous = reservationService.readRequestState(
                        userId,
                        request.getRequestId(),
                        request.getTicketCategoryId(),
                        request.getQuantity()
                );
        //如果不为空，则表示该用户相同的购买，已经
        if (previous != null) {
            //恢复已有订单结果，或返回处理中、已补偿等结果
            return recoverExistingRequest(userId, request, previous);
        }

        // 2. 新请求检查当前票档是否允许购买
        PurchaseInfo info = orderService.validatePurchase(request);

        // 3. 当允许购买时，执行Redis原子预扣，脚本内部仍然检查请求是否重复
        StockReservationResult result = reservationService.reserve(
                userId,
                request.getRequestId(),
                request.getTicketCategoryId(),
                request.getQuantity()
        );

        return switch (result) {
            // 本次已预扣成功（占票成功），由当前调用负责创建订单
            case RESERVED_NOW ->
                    createOrder(userId, request, info);

            // 相同请求已经被处理过，则恢复它的结果
            case RESERVED_BEFORE, ORDER_CREATED, COMPENSATED ->
                    recoverExistingRequest(userId, request, result);
        };
    }

    /**
     * 为同一用户、同一requestId的重试确认购买结果。
     * Redis请求记录用于判断处理阶段，MySQL订单用于确认实际建单结果。
     * 返回原订单ID，或提示结果未明、请求已结束；发现数据不一致时报错核对。
     */
    private Long recoverExistingRequest(
            Long userId,
            OrderCreateRequest request,
            StockReservationResult previous) {

        // 按用户ID和requestId查询MySQL，确认这次购买是否已经生成订单。
        TicketOrder existing = findExisting(userId, request.getRequestId());

        return switch (previous) {

            case RESERVED_BEFORE -> {
                // Redis只记录了预扣成功，但MySQL可能已提交订单，只是成功状态尚未回写Redis。
                if (existing != null) {
                    // 核对MySQL订单的票档和数量，返回原订单ID，避免同一次购买重复建单。
                    yield reuse(existing, request);
                }

                // MySQL暂未查到订单，原建单事务可能尚未提交，购买结果仍未确定。
                // 不能因此再次创建订单，也不能直接退回Redis预扣库存。
                throw new BusinessException("REQUEST_PROCESSING", "本次购买结果尚未确认，请稍后使用原请求标识查询结果");
            }

            case ORDER_CREATED -> {
                if (existing == null) {
                    // Redis请求记录显示已建单，但MySQL查不到这次购买对应的订单。
                    // 两边数据不一致，需要核对；不能用同一requestId再次建单或直接回补Redis库存。
                    throw new IllegalStateException(
                            "Redis记录已建单，但未查到订单，用户ID："
                                    + userId
                                    + "，请求标识："
                                    + request.getRequestId()
                    );
                }

                // MySQL已确认存在这次购买的订单，核对票档和数量后返回原订单ID。
                yield reuse(existing, request);
            }

            case COMPENSATED -> {
                if (existing != null) {
                    // Redis记录显示创建失败后的预扣库存已退回，但MySQL却存在对应订单。
                    // 可能出现订单仍占票、Redis却已把票重新放出的情况，需要停止自动处理并核对。
                    throw new IllegalStateException(
                            "请求已补偿但存在订单，订单ID：" + existing.getId()
                    );
                }

                // Redis记录显示预扣库存已退回，MySQL也未查到订单，本次购买已经结束。
                // 用户若要重新购买，必须使用新的requestId，重新校验购买条件并竞争库存。
                throw new BusinessException("REQUEST_CLOSED", "本次购买已失败并退回库存，如需购票请重新发起购买");
            }

            // 当前调用刚取得Redis预扣资格，应由submit()转入createOrder()创建订单。
            // 进入重复请求恢复分支属于程序调用错误。
            case RESERVED_NOW ->
                    throw new IllegalStateException("新预扣成功的请求应进入创建订单流程");
        };
    }

    private TicketOrder findExisting(Long userId, String requestId) {
        LambdaQueryWrapper<TicketOrder> query = new LambdaQueryWrapper<>();

        query.eq(TicketOrder::getUserId, userId).eq(TicketOrder::getRequestId, requestId);

        return orderMapper.selectOne(query);
    }

    private Long reuse(TicketOrder existing, OrderCreateRequest request) {

        // 同一请求标识不能用来购买其他票档或改变数量
        if (!Objects.equals(existing.getTicketCategoryId(), request.getTicketCategoryId()) || !Objects.equals(existing.getQuantity(), request.getQuantity())) {

            throw new BusinessException("REQUEST_PARAM_CONFLICT", "同一请求标识不能更改票档或购买数量"
            );
        }

        // 即使旧订单已取消或超时，也返回旧订单，不重新购买
        return existing.getId();
    }

    /**
     * 当前调用已取得Redis预扣资格（RESERVED_NOW），继续创建MySQL订单并处理结果。
     * MySQL确认成功后回写Redis；确认失败后补偿Redis；结果未明时保留预扣。
     */
    private Long createOrder(
            Long userId,
            OrderCreateRequest request,
            PurchaseInfo info) {

        Long orderId;
        ////第一个 try/catch 处理 MySQL 建单
        try {
            // MySQL在同一个事务内锁定库存、保存待支付订单。
            // 跨Bean调用正常返回后，MySQL事务已经提交。
            orderId = orderService.create(userId, request, info);

        } catch (BusinessException | DuplicateKeyException exception) {

            // 针对当前create()实现：业务拒绝或插入唯一键冲突，会触发MySQL事务回滚。
            // 再查询同一请求的订单，避免把已有订单对应的Redis库存错误退回。
            TicketOrder existing = findExisting(userId, request.getRequestId());

            if (existing != null) {
                // MySQL已存在这次购买的订单，核对购买参数后复用，不退回Redis库存。
                orderId = reuse(existing, request);

            } else {
                // 当前MySQL创建事务已回滚，且未查到同一请求的订单，
                // 可以退回当前调用取得的Redis预扣库存。
                try {
                    reservationService.compensate(
                            userId,
                            request.getRequestId(),
                            request.getTicketCategoryId(),
                            request.getQuantity()
                    );

                } catch (RuntimeException compensationException) {
                    // MySQL已确认建单失败，但Redis库存是否退回尚未确认。
                    // 保留原请求标识，后续根据Redis记录核对，不能盲目重新执行购买。
                    exception.addSuppressed(compensationException);

                    log.error(
                            "MySQL建单已回滚，但Redis库存补偿未确认，userId={}, requestId={}",
                            userId,
                            request.getRequestId(),
                            exception
                    );

                    throw new BusinessException("REQUEST_PROCESSING", "本次购买未完成，库存退回结果待确认，请保留原请求标识"
                    );
                }

                // Redis补偿已确认完成，向调用方返回原来的购买失败原因。
                throw exception;
            }

        } catch (RuntimeException exception) {

            // 数据库连接、提交或其他系统异常，无法仅凭异常判断MySQL是否已建单。
            // 先保留Redis预扣，查询同一请求的MySQL订单。
            log.error(
                    "MySQL建单结果待确认，暂不补偿Redis，userId={}, requestId={}",
                    userId,
                    request.getRequestId(),
                    exception
            );

            TicketOrder existing = findExisting(userId, request.getRequestId());

            if (existing == null) {
                // MySQL暂未查到订单，仍不能据此认定提交失败，不能直接退回Redis库存。
                throw new BusinessException("REQUEST_PROCESSING", "本次购买结果尚未确认，请稍后使用原请求标识查询结果"
                );
            }

            // MySQL已确认存在原订单，核对购买参数后恢复订单ID。
            orderId = reuse(existing, request);
        }
        ////第二个 try/catch 处理 Redis 回写 .Redis 回写失败不会进入前面的库存补偿分支。
        try {
            // MySQL已确认订单存在，再把订单ID和ORDER_CREATED状态写入Redis请求记录。
            reservationService.markOrderCreated(
                    userId,
                    request.getRequestId(),
                    request.getTicketCategoryId(),
                    request.getQuantity(),
                    orderId
            );

        } catch (RuntimeException exception) {
            // MySQL订单已经存在，Redis回写失败不能被当成建单失败，
            // 不能退回Redis库存；用户仍应拿到真实订单ID。
            log.error(
                    "MySQL订单已确认存在，但Redis请求状态回写失败，userId={}, requestId={}, orderId={}",
                    userId,
                    request.getRequestId(),
                    orderId,
                    exception
            );
        }

        return orderId;
    }


    /**
     * 异步抢票受理入口。
     *
     * 只做：
     * 1. 检查原请求是否已经受理
     * 2. 校验当前演出、场次和票档的购买条件
     * 3. 生成服务端购买快照
     * 4. Redis Lua原子预扣库存并写入Stream
     *
     * 不直接创建MySQL订单。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StockReservationResult submitAsync(
            Long userId,
            OrderCreateRequest request) {

        // 1. 校验调用参数
        if (userId == null || userId <= 0 || request == null) {
            throw new BusinessException(
                    "INVALID_PARAM",
                    "下单参数不合法"
            );
        }

        // 2. 优先查询同一次异步购买
        //
        // 必须先查询旧请求，不能先验证当前销售状态。
        // 因为用户重试时，演出可能已经停售，
        // 但原来受理的请求仍然需要返回原处理状态。
        StockReservationResult previous =
                reservationService.readAsyncRequestState(
                        userId,
                        request.getRequestId(),
                        request.getTicketCategoryId(),
                        request.getQuantity()
                );

        if (previous != null) {
            return previous;
        }

        // 已受理的购买在前面恢复结果。
       // 只有新购买才竞争当前实例的受理名额。
        orderAdmissionGuard.enter();


        //当这不是重复请求时，检查是否频繁购买
        try {
            purchaseRateLimiter.check(userId, request.getTicketCategoryId());

        } catch (BusinessException exception) {
            // 限流判断期间，另一个相同请求可能已经完成Redis预扣。
            // 再核对一次，避免将已经受理的购买误报为新购买被拒绝。
            StockReservationResult latest =
                    reservationService.readAsyncRequestState(
                            userId,
                            request.getRequestId(),
                            request.getTicketCategoryId(),
                            request.getQuantity()
                    );

            if (latest != null) {
                // 同一次购买已经受理，返回它的实际处理阶段。
                // 不再次预扣库存，也不再次登记建单任务。
                return latest;
            }

            // 当前仍未查到原购买已受理，保留本次限流拒绝结果。
            // 这不代表另一个并发调用之后一定不会受理成功。
            throw exception;
        }finally {
            // 正常返回、业务拒绝或系统异常，都会结束当前受理调用。
            // 后台建单由消息消费端继续处理，不再占用这个名额。
            orderAdmissionGuard.leave();
        }

        // 用户和票档额度允许后，再检查转发链路是否仍能承接新任务。
        // 积压达到阈值时，停止当前购买，此时尚未预扣库存。
        orderBacklogGuard.check();

        // 3. 只有新请求才检查是否允许购买
        //
        // 复用同步流程已有的校验能力。
        // 价格和演出时间均来自服务端。
        PurchaseInfo info = orderService.validatePurchase(request);

        // 4. 创建本次请求的服务端受理时间
        LocalDateTime acceptedAt =
                LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                        .truncatedTo(ChronoUnit.MILLIS);

        LocalDateTime eventStartTime =
                info.startTime()
                        .truncatedTo(ChronoUnit.MILLIS);

        // 5. 防止购买条件校验后，
        // 演出刚好已经开始。
        if (!acceptedAt.isBefore(eventStartTime)) {
            throw new BusinessException(
                    "SALE_ENDED",
                    "演出已开始，停止售票"
            );
        }

        // 6. 保证服务端价格快照能够合法写入订单
        BigDecimal unitPrice = info.price();

        if (unitPrice == null
                || unitPrice.signum() < 0
                || unitPrice.scale() > 2
                || unitPrice.compareTo(
                new BigDecimal("9999999999.99")
        ) > 0) {

            throw new IllegalStateException(
                    "票档价格快照不合法"
            );
        }

        // 7. 构造异步建单任务
        //
        // 用户ID来自登录身份。
        // 单价和演出时间来自服务端。
        OrderCreateMessage message =
                new OrderCreateMessage(
                        userId,
                        request.getRequestId(),
                        request.getTicketCategoryId(),
                        request.getQuantity(),
                        unitPrice,
                        eventStartTime,
                        acceptedAt
                );

        // 8. 执行Redis Lua原子预扣
        // 并将待建单任务写入Redis Stream
        //
        // 不直接调用MySQL建单方法。
        return reservationService.reserveAsync(message);
    }
}