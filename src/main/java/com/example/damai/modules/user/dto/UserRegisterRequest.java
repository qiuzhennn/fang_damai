package com.example.damai.modules.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 普通用户注册请求。
 * 不接收角色、状态和用户 ID，避免客户端自行指定权限。
 */
public class UserRegisterRequest {

    @NotBlank(message = "账号不能为空")
    @Pattern(
            regexp = "^[a-z0-9_]{4,30}$",
            message = "账号须为4到30位小写字母、数字或下划线"
    )
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须为8到64个字符")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 50, message = "昵称不能超过50个字符")
    private String nickname;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
}