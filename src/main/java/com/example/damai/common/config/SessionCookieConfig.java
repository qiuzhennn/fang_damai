package com.example.damai.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * 统一登录与退出登录使用的会话Cookie。
 *
 * Cookie携带会话ID，服务端根据这个ID从Redis恢复登录身份。
 */
@Configuration(proxyBeanMethods = false)
public class SessionCookieConfig {

    @Bean
    public CookieSerializer sessionCookieSerializer() {
        DefaultCookieSerializer cookie = new DefaultCookieSerializer();

        // 与SecurityConfig中退出登录清除的Cookie名称保持一致。
        cookie.setCookieName("JSESSIONID");

        // 本应用的登录、订单和退出接口统一使用这份会话Cookie。
        cookie.setCookiePath("/");

        // 会话ID属于登录凭据，页面脚本无需直接读取它。
        cookie.setUseHttpOnlyCookie(true);

        cookie.setSameSite("Lax");

        return cookie;
    }
}