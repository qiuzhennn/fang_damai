package com.example.damai.common.security;

import com.example.damai.common.response.ApiResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为前端提供 CSRF token。
 * 返回例子{
 *   "code": "SUCCESS",
 *   "data": {
 *     "headerName": "X-CSRF-TOKEN",
 *     "token": "Y_1Yx-zEGZrncvcFqziDnhNDS5qzNO0CCgkeIA-UCvXprGk6Vchq_on8e6PKE8U0zRW3qHV6ZqLRAIsvOTAqFGryPsOLnAtb"
 *   },
 *   "message": "操作成功"
 * }
 */
@RestController
public class CsrfController {
   /*
   * 注册、登录、下单等 POST/PUT 请求，需要：
    1. 先请求 GET /auth/csrf。
    2. 保留服务器返回的 Session Cookie。
    3. 把响应中的 headerName、token 放入后续请求头。
   * */
    @GetMapping("/auth/csrf")
    public ApiResponse<CsrfResponse> csrf(CsrfToken csrfToken) {
        return ApiResponse.success(new CsrfResponse(
                csrfToken.getHeaderName(),
                csrfToken.getToken()
        ));
    }

    public record CsrfResponse(
            String headerName,
            String token
    ) {
    }
}