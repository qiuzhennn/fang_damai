package com.example.damai.common.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import java.io.IOException;

/**
 * Session 登录和接口权限配置。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository contextRepository,
            CsrfTokenRepository csrfRepository) throws Exception {

        http
                // 保留 CSRF 防护，修改数据的请求需要携带 CSRF token
                //让 CsrfFilter 使用我们配置的仓库读取 token 。

                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                )
                .securityContext(context -> context
                        .securityContextRepository(contextRepository)
                        .requireExplicitSave(true)
                )

                // 不使用默认登录页面或 HTTP Basic 登录
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // API 不需要保存请求后再跳转到登录页面
                .requestCache(AbstractHttpConfigurer::disable)

                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                .authorizeHttpRequests(auth -> auth
                        // 错误分派与共用会话接口不属于某个业务角色。
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.GET, "/auth/me").authenticated()

                        // 登录注册入口公开，但 POST 仍保留 CSRF 校验。
                        .requestMatchers(HttpMethod.POST,
                                "/user/auth/register", "/user/auth/login",
                                "/admin/auth/login").permitAll()

                        // 只有原来的四类 GET 浏览接口公开，不公开整个前缀。
                        .requestMatchers(HttpMethod.GET,
                                "/public/event-categories",
                                "/public/events", "/public/events/*",
                                "/public/event-sessions", "/public/ticket-categories")
                        .permitAll()

                        // 普通用户与管理员的业务命名空间明确分离。
                        .requestMatchers("/user/**").hasRole("USER")
                        .requestMatchers("/admin/**").hasRole("ADMIN")

                        // 旧的混合路径不保留兼容别名，避免绕过新边界。
                        .anyRequest().denyAll()
                )

                // 安全过滤器中的异常，直接输出统一 JSON
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(
                                        response, 401,
                                        "UNAUTHENTICATED", "请先登录"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(
                                        response, 403,
                                        "ACCESS_DENIED",
                                        "无访问权限或CSRF校验失败"))

                )

                .logout(logout -> logout
                        // 开启 CSRF 时，此退出接口使用 POST 请求
                        .logoutUrl("/auth/logout")

                        // 使服务器 Session 失效，清除认证信息和会话 Cookie
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")

                        // 不跳转登录页，返回统一 JSON
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setStatus(HttpServletResponse.SC_OK);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    "{\"code\":\"SUCCESS\","
                                            + "\"message\":\"退出成功\","
                                            + "\"data\":true}"
                            );
                        })
                        .permitAll()
                );





        return http.build();
    }

    /**
     * 这里只写固定的错误码和说明，不拼接用户输入。
     */
    private static void writeError(
            HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {

        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":\"" + code
                        + "\",\"message\":\"" + message
                        + "\",\"data\":null}"
        );
    }

    /**
     * 将登录身份保存在 HttpSession 中。
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 将 CSRF token 保存在同一个 Session 中。
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }
}