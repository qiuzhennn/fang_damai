package com.example.damai.modules.order.ratelimit;

import org.springframework.stereotype.Component;
import com.example.damai.common.exception.BusinessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;

/**
 * 控制新购买请求进入抢票流程的速度。
 *
 * 用户额度：防止同一用户短时间内频繁发起不同购买。
 * 票档额度：限制所有用户集中抢同一票档时的受理速度。
 */
@Component
public class PurchaseRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;

    private final int userCapacity;
    private final int userRate;
    private final int ticketCapacity;
    private final int ticketRate;

    public PurchaseRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.order.rate-limit.user-capacity:3}")
            int userCapacity,
            @Value("${app.order.rate-limit.user-refill-per-second:1}")
            int userRate,
            @Value("${app.order.rate-limit.ticket-capacity:100}")
            int ticketCapacity,
            @Value("${app.order.rate-limit.ticket-refill-per-second:100}")
            int ticketRate) {

        // 配置错误时直接阻止应用启动，
        // 避免启动成功后，所有购买才因错误配置而无法通过。
        validateConfig(userCapacity, userRate);
        validateConfig(ticketCapacity, ticketRate);

        this.redisTemplate = redisTemplate;
        this.userCapacity = userCapacity;
        this.userRate = userRate;
        this.ticketCapacity = ticketCapacity;
        this.ticketRate = ticketRate;

        this.rateLimitScript = new DefaultRedisScript<>();
        this.rateLimitScript.setLocation(new ClassPathResource("lua/purchase_rate_limit.lua"));
        this.rateLimitScript.setResultType(Long.class);
    }

    /**
     * 检查本次新购买是否允许继续。
     *
     * 正常返回：允许继续校验购买条件、竞争库存。
     * 抛出异常：停止本次调用，不预扣库存、不登记建单任务。
     *
     * @param userId 当前登录用户ID，由后端提供
     * @param ticketCategoryId 本次购买的票档ID
     */
    public void check(Long userId, Long ticketCategoryId) {

        // 同一个用户跨票档共用额度，避免不停切换票档绕过用户限流。
        String userKey = "fang-damai:order:rate:user:" + userId;

        // 所有用户竞争这个票档的受理额度，控制热点票档的进入速度。
        String ticketKey = "fang-damai:order:rate:ticket:" + ticketCategoryId;

        Long result = redisTemplate.execute(
                rateLimitScript,
                List.of(userKey, ticketKey),
                Integer.toString(userCapacity),
                Integer.toString(userRate),
                Integer.toString(ticketCapacity),
                Integer.toString(ticketRate)
        );

        if (Long.valueOf(0L).equals(result)) {
            // 两个额度都允许，继续购买流程。
            // 此时只取得进入资格，还没有预扣门票。
            return;
        }

        if (Long.valueOf(1L).equals(result)) {
            throw new BusinessException(
                    "USER_RATE_LIMITED",
                    "操作过于频繁，请稍后重试"
            );
        }

        if (Long.valueOf(2L).equals(result)) {
            throw new BusinessException(
                    "TICKET_BUSY",
                    "当前票档抢票繁忙，请稍后重试"
            );
        }

        // 无法确认限流结果时停止新购买，不能当作允许继续占票。
        throw new IllegalStateException("抢票限流未返回有效结果，返回值：" + result);
    }

    private static void validateConfig(int capacity, int rate) {
        if (capacity < 1 || capacity > 1_000_000
                || rate < 1 || rate > 1_000_000
                || capacity > (long) rate * 86_400) {

            throw new IllegalArgumentException(
                    "限流容量和补充速度必须在1至1000000之间，"
                            + "从空桶补满所需时间不得超过一天"
            );
        }
    }
}