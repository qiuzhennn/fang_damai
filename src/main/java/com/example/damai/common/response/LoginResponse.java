package com.example.damai.common.response;

/**
 * 登录成功后返回的公开账号信息。
 *
 * @param id 当前账号 ID
 * @param username 登录账号
 * @param nickname 展示名称
 * @param accountType 账号类型：USER 或 ADMIN
 */
public record LoginResponse(
        Long id,
        String username,
        String nickname,
        String accountType
) {
}