package com.example.damai.modules.ticket.redis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class RedisConnectionTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void shouldWriteAndReadString() {
        // 每次使用独立的测试 Key，避免覆盖库存或其他业务数据
        String key = "fang-damai:test:connection:" + UUID.randomUUID();

        try {
            // 写入字符串，并设置一分钟过期，防止测试数据长期残留
            redisTemplate.opsForValue().set(key, "connected", Duration.ofMinutes(1));

            String value = redisTemplate.opsForValue().get(key);

            // 不仅检查网络连通，还确认认证、写入和读取都正常
            assertEquals("connected", value);
        } finally {
            // 测试结束后，只清理本次创建的 Key
            redisTemplate.delete(key);
        }
    }
}