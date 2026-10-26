package com.example.damai.modules.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.user.dto.UserLoginRequest;
import com.example.damai.modules.user.dto.UserRegisterRequest;
import com.example.damai.modules.user.entity.UserAccount;
import com.example.damai.modules.user.mapper.UserAccountMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 普通用户账号业务。
 */
@Service
public class UserAccountService {
    // 启动时生成一次，不要在每个登录请求中重新生成
    private final String dummyPasswordHash;
    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;


    public UserAccountService(UserAccountMapper userAccountMapper, PasswordEncoder passwordEncoder) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;

        // 仅用于不存在账号时的耗时平衡，不对应任何真实账号
        this.dummyPasswordHash = passwordEncoder.encode(
                java.util.UUID.randomUUID().toString());
    }

    /**
     * 注册普通用户，不自动登录。
     *
     * @param request 已通过 @Valid 校验的注册信息
     * @return 是否注册成功
     */
    public boolean register(UserRegisterRequest request) {
        String password = request.getPassword();

        // BCrypt 限制的是字节数，中文等字符可能占多个 UTF-8 字节
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(
                    "INVALID_PASSWORD", "密码的UTF-8编码长度不能超过72字节"
            );
        }

        String username = request.getUsername();

        LambdaQueryWrapper<UserAccount> query = new LambdaQueryWrapper<>();
        query.eq(UserAccount::getUsername, username);

        // 提前查重，为通常的重复注册提供明确提示
        if (userAccountMapper.selectCount(query) > 0) {
            throw new BusinessException("USERNAME_ALREADY_EXISTS", "账号已存在");
        }

        UserAccount account = new UserAccount();
        account.setUsername(username);

        // 只保存密码哈希，不保存或打印明文密码
        account.setPasswordHash(passwordEncoder.encode(password));

        account.setNickname(request.getNickname().trim());

        // 新注册普通用户默认启用，客户端不能指定状态
        account.setStatus(1);
        //这个方法只有一次数据库写入，不需要协调多项写操作；账号唯一性由唯一索引保证。无需添加事务
        try {
            // 只插入一次
            return userAccountMapper.insert(account) == 1;
        } catch (DuplicateKeyException exception) {
            // 并发注册同一账号时，由数据库唯一索引保证最终不重复
            throw new BusinessException("USERNAME_ALREADY_EXISTS", "账号已存在");
        }
    }

    /**
     * 验证普通用户账号和密码。
     *
     * @return 验证通过的账号，仅供后端内部使用
     */
    public UserAccount authenticate(UserLoginRequest request) {
        String password = request.getPassword();

        // 与注册时保持一致，避免超长密码进入 BCrypt 校验
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalidCredentials();
        }

        LambdaQueryWrapper<UserAccount> query =
                new LambdaQueryWrapper<>();
        query.eq(UserAccount::getUsername, request.getUsername());

        UserAccount account = userAccountMapper.selectOne(query);

        // 账号不存在时仍做一次哈希匹配，减少明显的处理耗时差异
        String passwordHash = account == null
                ? dummyPasswordHash
                : account.getPasswordHash();

        boolean passwordMatches = passwordEncoder.matches(password, passwordHash);

        // 不区分账号不存在和密码错误，统一向客户端提示
        if (account == null || !passwordMatches) {
            throw invalidCredentials();
        }

        // 密码正确后，再检查账号是否允许登录
        if (!Integer.valueOf(1).equals(account.getStatus())) {
            throw new BusinessException("ACCOUNT_DISABLED", "账号已被禁用");
        }

        return account;
    }

    /**
     * 统一的账号密码验证失败异常。
     */
    private BusinessException invalidCredentials() {
        return new BusinessException(
                "INVALID_CREDENTIALS", "账号或密码错误");
    }
}