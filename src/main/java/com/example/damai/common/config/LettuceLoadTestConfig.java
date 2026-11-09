package com.example.damai.common.config;

import io.lettuce.core.TimeoutOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 临时压测对照配置。
 * 验证 Redis 客户端的命令到期调度是否造成 CPU 竞争。
 * 只有显式开启压测开关时才生效，验证结束后删除。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "app.loadtest.disable-command-expiry",
        havingValue = "true"
)
public class LettuceLoadTestConfig {

    @Bean
    public LettuceClientOptionsBuilderCustomizer disableCommandExpiry() {
        return builder -> builder.timeoutOptions(
                TimeoutOptions.builder()
                        .timeoutCommands(false)
                        .build()
        );
    }
}