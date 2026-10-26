package com.example.damai.common.security;

import com.example.damai.common.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 共用会话身份查询；普通用户、管理员均可查询自己的当前身份。 */
@RestController
@RequestMapping("/auth")
public class SessionIdentityController {


    @GetMapping("/me")
    public ApiResponse<LoginUser> me(
            @AuthenticationPrincipal LoginUser loginUser) {

        return ApiResponse.success(loginUser);
    }
}
