package com.example.damai.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 普通用户和管理员共用的密码哈希配置。
 */
@Configuration
public class PasswordConfig {
    /*
    * 后面注册时调用 encode 生成哈希，登录时调用 matches 校验。
    * BCrypt 会生成随机盐，所以同一密码生成的哈希可能不同，不能重新哈希后用字符串相等判断密码。
    * */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}