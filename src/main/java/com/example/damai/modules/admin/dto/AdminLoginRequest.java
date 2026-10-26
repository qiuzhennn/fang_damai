package com.example.damai.modules.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员登录请求，不允许客户端指定权限。
 */
public class AdminLoginRequest {

    @NotBlank(message = "管理员账号不能为空")
    @Size(max = 50, message = "账号不能超过50个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码不能超过64个字符")
    private String password;

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
}