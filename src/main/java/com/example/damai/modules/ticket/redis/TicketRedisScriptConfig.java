package com.example.damai.modules.ticket.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 注册库存 Lua 脚本，避免每次请求重新创建脚本对象。
 */
@Configuration
public class TicketRedisScriptConfig {



    @Bean
    public DefaultRedisScript<Long> ticketStockReserveScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/ticket_stock_reserve.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public DefaultRedisScript<Long> ticketRequestMarkCreatedScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(
                "lua/ticket_request_mark_created.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public DefaultRedisScript<Long> ticketStockCompensateScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(
                "lua/ticket_stock_compensate.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public DefaultRedisScript<Long> ticketStockReleaseClosedScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(
                "lua/ticket_stock_release_closed.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public DefaultRedisScript<Long> ticketStockCancelWarmupScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(
                "lua/ticket_stock_cancel_warmup.lua"));
        script.setResultType(Long.class);
        return script;
    }


    /**
     * 异步库存预扣 Lua。
     * 同时执行库存预扣和 Stream 任务登记。
     */
    @Bean
    public DefaultRedisScript<Long> ticketStockReserveAsyncScript() {

        DefaultRedisScript<Long> script =
                new DefaultRedisScript<>();

        script.setLocation(new ClassPathResource("lua/ticket_stock_reserve_async.lua"));

        script.setResultType(Long.class);

        return script;
    }
}