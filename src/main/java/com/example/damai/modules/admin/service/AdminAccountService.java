package com.example.damai.modules.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.admin.dto.AdminLoginRequest;
import com.example.damai.modules.admin.entity.AdminAccount;
import com.example.damai.modules.admin.mapper.AdminAccountMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 管理员账号认证，不提供公开注册。
 */
@Service
public class AdminAccountService {

    private final AdminAccountMapper adminAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    public AdminAccountService(
            AdminAccountMapper adminAccountMapper,
            PasswordEncoder passwordEncoder) {
        this.adminAccountMapper = adminAccountMapper;
        this.passwordEncoder = passwordEncoder;

        // 不存在账号时仍执行密码匹配，减少明显的耗时差异
        this.dummyPasswordHash =
                passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 验证管理员密码与账号状态，不直接建立登录会话。
     */
    public AdminAccount authenticate(AdminLoginRequest request) {
        String password = request.getPassword();

        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalidCredentials();
        }

        LambdaQueryWrapper<AdminAccount> query =
                new LambdaQueryWrapper<>();
        query.eq(AdminAccount::getUsername, request.getUsername());

        AdminAccount account = adminAccountMapper.selectOne(query);

        String hash = account == null
                ? dummyPasswordHash
                : account.getPasswordHash();

        boolean matches = passwordEncoder.matches(password, hash);

        if (account == null || !matches) {
            throw invalidCredentials();
        }

        if (!Integer.valueOf(1).equals(account.getStatus())) {
            throw new BusinessException(
                    "ACCOUNT_DISABLED", "账号已被禁用");
        }

        return account;
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(
                "INVALID_CREDENTIALS", "账号或密码错误");
    }
}