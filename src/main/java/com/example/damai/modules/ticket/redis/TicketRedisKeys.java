package com.example.damai.modules.ticket.redis;

/**
 * 票务 Redis Key 定义。
 * 统一生成 Key，避免业务代码到处拼接字符串。
 */
public final class TicketRedisKeys {

    private static final String STOCK_PREFIX = "fang-damai:ticket:stock:";

    private static final String REQUEST_PREFIX = "fang-damai:ticket:request:";

    /**
     * 异步建单的 Redis Stream。
     *
     * 用于保存已经获得 Redis 预扣资格，
     * 但尚未完成 RabbitMQ 可靠转发的建单任务。
     */
    public static String orderCreateOutboxStream() {
        return "fang-damai:order:create:outbox";
    }




    // 工具类不需要创建实例
    private TicketRedisKeys() {
    }

    /**
     * 获取指定票档的库存 Key。
     * 用于保存可售数量，在用户下单、取消、超时等场景下需要更新缓存
     */
    public static String stock(Long ticketCategoryId) {
        return STOCK_PREFIX + ticketCategoryId;
    }

    /**
     * 当前用户的一次购买请求记录。
     * userId 必须来自服务器登录身份，不能接受客户端自行指定。
     */
    public static String request(Long userId, String requestId) {
        return REQUEST_PREFIX + userId + ":" + requestId;
    }

    /**
     * 指定票档的购买条件缓存。
     * 用于保存价格、状态、开售时间等，在演出、场次、票档资料变化时需要更新缓存
     */
    public static String purchaseInfo(Long ticketCategoryId) {return "fang-damai:ticket:purchase-info:" + ticketCategoryId;}

    /**
     * 购买信息缓存的当前版本号。
     */
    public static String purchaseInfoVersion() {
        return "fang-damai:ticket:purchase-info-version";
    }

    // TODO: 当前按单机Redis设计；接入Redis Cluster前，需重新设计库存与请求Key的同槽方案，不能直接沿用多Key脚本。
}