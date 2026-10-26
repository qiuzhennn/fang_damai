package com.example.damai.modules.admin.controller.admin;

import com.example.damai.common.response.ApiResponse;
import com.example.damai.common.response.LoginResponse;
import com.example.damai.common.security.LoginUser;
import com.example.damai.common.security.SessionLoginService;
import com.example.damai.modules.admin.dto.AdminLoginRequest;
import com.example.damai.modules.admin.entity.AdminAccount;
import com.example.damai.modules.admin.service.AdminAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员认证接口。
 */
@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private final AdminAccountService adminAccountService;
    private final SessionLoginService sessionLoginService;

    public AdminAuthController(
            AdminAccountService adminAccountService,
            SessionLoginService sessionLoginService) {
        this.adminAccountService = adminAccountService;
        this.sessionLoginService = sessionLoginService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody AdminLoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {

        AdminAccount account = adminAccountService.authenticate(body);

        // 管理员身份只由后端在验证管理员表后创建
        LoginUser loginUser = new LoginUser(
                account.getId(),
                account.getUsername(),
                LoginUser.AccountType.ADMIN
        );

        sessionLoginService.login(loginUser, request, response);

        return ApiResponse.success(new LoginResponse(
                account.getId(),
                account.getUsername(),
                account.getNickname(),
                loginUser.accountType().name()
        ));
    }
}
