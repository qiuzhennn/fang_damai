package com.example.damai.modules.admin.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.damai.modules.admin.entity.AdminAccount;
import com.example.damai.modules.admin.mapper.AdminAccountMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;

/**
 * 首个管理员的一次性初始化。
 * 默认不启用，不覆盖已有账号密码。
 */
@Configuration
@ConditionalOnProperty(
        name = "app.admin-bootstrap.enabled",
        havingValue = "true"
)
public class AdminBootstrapConfig {

    @Bean
    public CommandLineRunner initializeAdmin(
            AdminAccountMapper mapper,
            PasswordEncoder encoder,
            @Value("${app.admin-bootstrap.username}") String username,
            @Value("${app.admin-bootstrap.password}") String password) {

        return args -> {
            if (!username.matches("[a-z0-9_]{4,30}")) {
                throw new IllegalArgumentException(
                        "初始化管理员账号须为4到30位小写字母、数字或下划线");
            }

            if (password.isBlank()
                    || password.length() < 12
                    || password.length() > 64
                    || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalArgumentException(
                        "初始化管理员密码须为12到64个字符且不超过72字节");
            }

            LambdaQueryWrapper<AdminAccount> query =
                    new LambdaQueryWrapper<>();
            query.eq(AdminAccount::getUsername, username);

            // 已有同名管理员时不修改账号或重置密码
            if (mapper.selectCount(query) > 0) {
                return;
            }

            AdminAccount account = new AdminAccount();
            account.setUsername(username);
            account.setPasswordHash(encoder.encode(password));
            account.setNickname("系统管理员");
            account.setStatus(1);

            if (mapper.insert(account) != 1) {
                throw new IllegalStateException("初始化管理员失败");
            }
        };
    }
}