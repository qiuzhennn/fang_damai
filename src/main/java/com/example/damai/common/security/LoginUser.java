package com.example.damai.common.security;

import java.io.Serializable;

/**
 * 服务器登录上下文中保存的身份。
 * 不保存明文密码或密码哈希。
 *
 * @param id 当前账号 ID
 * @param username 登录账号
 * @param accountType 账号类型，区分普通用户和管理员
 */
public record LoginUser(Long id, String username, AccountType accountType) implements Serializable {

    /**
     * 两张账号表的 ID 可能相同，必须同时使用账号类型区分身份。
     */
    public enum AccountType {
        USER,
        ADMIN
    }
}