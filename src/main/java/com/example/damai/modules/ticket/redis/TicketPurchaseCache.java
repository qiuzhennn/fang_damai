package com.example.damai.modules.ticket.redis;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.program.entity.Event;
import com.example.damai.modules.program.entity.EventSession;
import com.example.damai.modules.program.mapper.EventMapper;
import com.example.damai.modules.program.mapper.EventSessionMapper;
import com.example.damai.modules.ticket.entity.TicketCategory;
import com.example.damai.modules.ticket.mapper.TicketCategoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 负责读取、缓存购买所需的资料：
 * 票价、演出状态、场次状态、开售时间、演出开始时间。
 *
 * 作用：缓存命中时，从 Redis 读取这份信息，再判断是否允许购买，减少每次购买都要查询票档、场次、演出三张表。
 * 读取流程：
 * 1. 先查询 Redis。
 * 2. 缓存存在且有效，直接返回。
 * 3. 缓存不存在或已过期，查询数据库，再写入 Redis。
 */
@Component
public class TicketPurchaseCache {

    private static final Logger log = LoggerFactory.getLogger(TicketPurchaseCache.class);
    // 处理缓存击穿：加载锁的有效期，防止持锁请求异常退出后，锁一直无法释放
    private static final Duration LOAD_LOCK_TTL = Duration.ofSeconds(10);
    // 处理缓存击穿：没取得锁的请求，等待缓存生成的时间预算：300毫秒
    private static final long LOAD_WAIT_NANOS = Duration.ofMillis(300).toNanos();
    /**
     * 释放锁时，必须确认锁仍属于当前请求。
     *脚本仅在调用者仍持有锁时才释放该锁，因此超时后被其他人重新获取的锁不会被误释放：
     * KEYS[1]：加载锁Key
     * ARGV[1]：当前请求持有的随机标识
     */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
                """
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                end
                return 0
                """, Long.class);

    // 缓存有效期：30秒
    private static final Duration TTL = Duration.ofSeconds(30);

    // 用于操作 Redis，这里使用它读取、写入 Hash，并设置过期时间
    private final StringRedisTemplate redisTemplate;

    // 分别查询票档表、场次表、演出表
    private final TicketCategoryMapper categoryMapper;
    private final EventSessionMapper sessionMapper;
    private final EventMapper eventMapper;

    public TicketPurchaseCache(StringRedisTemplate redisTemplate, TicketCategoryMapper categoryMapper, EventSessionMapper sessionMapper, EventMapper eventMapper) {
        this.redisTemplate = redisTemplate;
        this.categoryMapper = categoryMapper;
        this.sessionMapper = sessionMapper;
        this.eventMapper = eventMapper;
    }

    /**
     * 对外提供的读取方法。
     * @param categoryId 票档ID
     * @return 这个票档对应的购买信息
     */
    public PurchaseInfo get(Long categoryId) {
        // 第一步：检查传入的票档ID是否合法
        if (categoryId == null || categoryId <= 0) {
            throw new BusinessException("INVALID_PARAM", "票档ID必须大于0");
        }
        // 根据票档ID生成 Redis Key
        // 先确定本次读取使用的缓存版本
        String version = currentVersion();
        // 将版本号加入缓存Key，不同版本的资料分别存放
        String key = TicketRedisKeys.purchaseInfo(categoryId) + ":" + version;

        // 第一遍检查：命中redis缓存，直接返回，不申请锁
        PurchaseInfo cached = readCache(categoryId, key);
        if (cached != null) {
            return cached;
        }//否则需要首个下单请求，拿到对应的加载锁加载，同一种下单类型的请求等待

        // 不同票档、不同缓存版本，分别使用各自的加载锁
        String lockKey = key + ":load-lock";
        // 标识本次持锁者，释放锁时需要核对
        String token = UUID.randomUUID().toString();
        /*
         * Key不存在时才设置，并同时设置10秒过期时间。
         * 同一个锁Key，在锁有效期间只有一个请求能取得锁。
         */
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                lockKey,
                token,
                LOAD_LOCK_TTL
        );
        if (acquired == null) {
            throw new IllegalStateException("购买资料加载锁执行结果为空");
        }
        if (!acquired) {
            // 别的请求正在加载，短暂等待读取它生成的缓存
            return awaitCache(categoryId, key);
        }
        try {
            /*
             * 第二遍检查：
             * 从第一次查缓存到成功取得锁之间，
             * 其他请求可能已经完成加载并释放了锁。
             * 再查一次，避免重复查询数据库。
             */
            cached = readCache(categoryId, key);
            if (cached != null) {
                return cached;
            }
            // 取得锁，并再次确认缓存缺失后，才查询数据库。
            PurchaseInfo info = loadFromDatabase(categoryId);
            // 写入缓存，让后续请求复用
            writeCache(key, info);

            return info;
        } finally {
            // 查询成功、查询失败，都尝试释放自己持有的锁
            releaseLoadLock(lockKey, token);
        }
    }




    /**
     * 从数据库组装购买资料。
     *
     * 关联关系：
     * 票档保存 sessionId，场次保存 eventId。
     * 因此先查票档，再查场次，最后查演出。
     */
    private PurchaseInfo loadFromDatabase(Long categoryId) {
        // 第一次查询：根据票档ID查询票档
        TicketCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException("TICKET_CATEGORY_NOT_FOUND", "票档不存在");
        }
        // 第二次查询：根据票档所属的场次ID查询场次
        EventSession session = sessionMapper.selectById(category.getSessionId());
        if (session == null) {
            throw new BusinessException("SESSION_NOT_FOUND", "场次不存在");
        }
        // 第三次查询：根据场次所属的演出ID查询演出
        Event event = eventMapper.selectById(session.getEventId());
        if (event == null) {
            throw new BusinessException("EVENT_NOT_FOUND", "演出不存在");
        }

        return new PurchaseInfo(
                category.getId(),           // 票档ID
                session.getId(),            // 场次ID
                event.getId(),              // 演出ID
                category.getPrice(),        // 当前票价
                event.getStatus(),          // 演出上架状态
                session.getStatus(),        // 场次启售状态
                session.getSaleStartTime(), // 开售时间
                session.getStartTime()      // 演出开始时间
        );
    }

    /**
     * 将 Redis 中的字符串资料转换成 Java 对象。
     *
     * 写入缓存时进行了“对象 -> 字符串”的转换，
     * 这里进行“字符串 -> 对象”的转换。
     */
    private PurchaseInfo decode(
            Long categoryId, Map<String, String> values) {
        return new PurchaseInfo(
                categoryId,
                Long.valueOf(values.get("sessionId")),
                Long.valueOf(values.get("eventId")),
                new BigDecimal(values.get("price")),
                Integer.valueOf(values.get("eventStatus")),
                Integer.valueOf(values.get("sessionStatus")),
                LocalDateTime.parse(values.get("saleStartTime")),
                LocalDateTime.parse(values.get("startTime"))
        );
    }

    /**
     * 缓存的当前购买资料，不是订单金额快照。
     */
    public record PurchaseInfo(
            Long ticketCategoryId,
            Long sessionId,
            Long eventId,
            BigDecimal price,
            Integer eventStatus,
            Integer sessionStatus,
            LocalDateTime saleStartTime,
            LocalDateTime startTime
    ) {
    }

    /**
     * 获取购买信息缓存的当前版本。
     */
    private String currentVersion() {
        String versionKey = TicketRedisKeys.purchaseInfoVersion();

        // 正常情况下，直接读取已经存在的版本号
        String version = redisTemplate.opsForValue().get(versionKey);

        if (version == null) {
            /*
             * 首次使用时初始化版本。
             *
             * setIfAbsent：只有Key不存在时才写入。
             * 多个请求同时初始化时，只有一个请求能够写入成功。
             */
            redisTemplate.opsForValue().setIfAbsent(versionKey, UUID.randomUUID().toString());

            // 重新读取，使用实际写入Redis的版本号
            version = redisTemplate.opsForValue().get(versionKey);
        }

        if (version == null || version.isBlank()) {
            throw new IllegalStateException("购买资料缓存版本读取失败");
        }

        return version;
    }


    /**
     * 切换购买信息缓存版本。
     */
    public void invalidateAfterCommit() {

        // 切换为全新的版本号
        Runnable invalidate = () ->
                redisTemplate.opsForValue().set(
                        TicketRedisKeys.purchaseInfoVersion(),
                        UUID.randomUUID().toString()
                );

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            /*
             * 当前有Spring事务同步：
             * 先登记回调，等事务成功提交后再切换版本。
             * 如果事务回滚，afterCommit不会执行。afterCommit() 是 Spring 提供的事务成功提交回调，避免数据库尚未提交时，就让请求开始加载新版本资料。
             */
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            invalidate.run();
                        }
                    }
            );

            return;
        }

        /*
         * 当前没有Spring事务同步：
         * 现有管理方法中的Mapper更新已完成自动提交，
         * 因此可以立即切换版本。
         */
        invalidate.run();
    }

    /**
     * 读取指定Key的缓存。
     * 返回null，表示缓存不存在或内部有效期已过。
     */
    private PurchaseInfo readCache(Long categoryId, String key) {
        Map<String, String> values = redisTemplate.<String, String>opsForHash().entries(key);

        if (values.isEmpty()) {
            return null;
        }

        try {
            long validUntil = Long.parseLong(values.get("validUntil"));

            if (System.currentTimeMillis() >= validUntil) {
                return null;
            }

            return decode(categoryId, values);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "购买条件缓存数据异常，票档ID：" + categoryId,
                    exception
            );
        }
    }

    /**
     * 将查出的购买资料写入缓存。
     */
    private void writeCache(String key, PurchaseInfo info) {
        Map<String, String> data = Map.of(
                "sessionId", info.sessionId().toString(),
                "eventId", info.eventId().toString(),
                "price", info.price().toPlainString(),
                "eventStatus", info.eventStatus().toString(),
                "sessionStatus", info.sessionStatus().toString(),
                "saleStartTime", info.saleStartTime().toString(),
                "startTime", info.startTime().toString(),
                "validUntil",
                Long.toString(System.currentTimeMillis() + TTL.toMillis())
        );

        redisTemplate.<String, String>opsForHash().putAll(key, data);
        redisTemplate.expire(key, TTL);
    }

/**
 * 没取得锁的请求，短暂等待其他请求生成缓存。
 */
private PurchaseInfo awaitCache(Long categoryId, String key) {
    long startedAt = System.nanoTime();

    while (true) {
        PurchaseInfo cached = readCache(categoryId, key);
        if (cached != null) {
            return cached;
        }

        // 使用nanoTime计算等待时长，避免系统时间调整造成影响
        if (System.nanoTime() - startedAt >= LOAD_WAIT_NANOS) {
            throw new BusinessException(
                    "PURCHASE_INFO_LOADING",
                    "购票资料加载中，请稍后重试"
            );
        }

        try {
            // 随机间隔15～30毫秒，避免等待者总在同一时刻查询Redis
            Thread.sleep(
                    ThreadLocalRandom.current().nextLong(15, 31)
            );
        } catch (InterruptedException exception) {
            // 保留线程中断标记，结束当前等待
            Thread.currentThread().interrupt();

            throw new BusinessException(
                    "PURCHASE_INFO_LOADING",
                    "购票资料加载等待已中断，请稍后重试"
            );
        }
    }
}

/**
 * 只释放属于当前请求的加载锁。
 */
private void releaseLoadLock(String lockKey, String token) {
    try {
        redisTemplate.execute(
                RELEASE_LOCK_SCRIPT,
                List.of(lockKey),
                token
        );
    } catch (RuntimeException exception) {
        // 释放失败时依靠锁的有效期清理，同时记录日志
        // 避免释放异常覆盖前面查询、解析时发生的原始异常
        log.warn("释放购买资料加载锁失败，key={}", lockKey, exception);
    }
}


    // TODO: 加载锁租期为10秒，慢查询超过租期时仍可能出现重复回源；
//       需结合数据库查询超时、加载耗时监控评估租期及续期方案。
// TODO: 对不存在的票档增加短期空值缓存，避免反复查询不存在的数据。
}