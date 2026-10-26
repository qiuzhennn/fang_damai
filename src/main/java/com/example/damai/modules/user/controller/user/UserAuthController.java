package com.example.damai.modules.user.controller.user;

import com.example.damai.common.response.ApiResponse;
import com.example.damai.common.response.LoginResponse;
import com.example.damai.common.security.LoginUser;
import com.example.damai.common.security.SessionLoginService;
import com.example.damai.modules.user.dto.UserLoginRequest;
import com.example.damai.modules.user.dto.UserRegisterRequest;
import com.example.damai.modules.user.entity.UserAccount;
import com.example.damai.modules.user.service.UserAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 普通用户认证接口。
 */
@RestController
@RequestMapping("/user/auth")
public class UserAuthController {

    private final UserAccountService userAccountService;
    private final SessionLoginService sessionLoginService;

    public UserAuthController(
            UserAccountService userAccountService,
            SessionLoginService sessionLoginService) {
        this.userAccountService = userAccountService;
        this.sessionLoginService = sessionLoginService;
    }

    /**
     * 注册普通用户，成功后仍需单独登录。
     */
    @PostMapping("/register")
    public ApiResponse<Boolean> register(
            @Valid @RequestBody UserRegisterRequest request) {

        return ApiResponse.success(userAccountService.register(request)
        );
    }

    /**
     * 普通用户登录，成功后通过 Session Cookie 保持登录状态。
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody UserLoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {

        // 先验证密码及账号状态
        UserAccount account = userAccountService.authenticate(body);

        // 普通用户登录入口，只创建 USER 身份
        LoginUser loginUser = new LoginUser(
                account.getId(),
                account.getUsername(),
                LoginUser.AccountType.USER
        );

        sessionLoginService.login(loginUser, request, response);

        // 返回公开信息，不返回密码哈希
        return ApiResponse.success(new LoginResponse(
                account.getId(),
                account.getUsername(),
                account.getNickname(),
                loginUser.accountType().name()
        ));
    }

}
