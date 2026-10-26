package com.example.damai.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 普通用户和管理员共用的 Session 登录处理。
 * 只能在账号密码验证成功后调用。
 */
@Service
public class SessionLoginService {

    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfRepository;

    public SessionLoginService(
            SecurityContextRepository contextRepository,
            CsrfTokenRepository csrfRepository) {
        this.contextRepository = contextRepository;
        this.csrfRepository = csrfRepository;
    }

    public void login(
            LoginUser loginUser,
            HttpServletRequest request,
            HttpServletResponse response) {

        // 权限来源于后端构造的账号类型，不读取客户端提交的角色
        String authority = "ROLE_" + loginUser.accountType().name();

        /*
        * 用户 登录后 把这个认证对象保存到了 Session 中
        * 后续 浏览器 携带 Session Cookie 请求订单接口，
        * Spring Security 就能恢复身份，
        * @AuthenticationPrincipal 再把其中的 LoginUser 取出来
        * */
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        loginUser,
                        null,
                        List.of(new SimpleGrantedAuthority(authority))
                );

        // 登录成功后更换 Session ID，防止会话固定攻击
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, request, response);

        // 清除登录前的 CSRF token，客户端登录后需重新获取
        new CsrfAuthenticationStrategy(csrfRepository).onAuthentication(authentication, request, response);

        // 创建新的上下文，避免修改已有的共享上下文对象
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);

        SecurityContextHolder.setContext(context);

        // 显式保存到 Session，使后续请求仍能识别当前身份
        contextRepository.saveContext(context, request, response);
    }
}