package com.example.damai.common.exception;

import com.example.damai.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理数据库访问异常。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDatabaseException(DataAccessException exception) {

        // 完整异常只记录在后端日志中
        log.error("数据库访问异常", exception);
        return internalError();
    }

    /**
     * 兜底处理没有被其他处理方法匹配的异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleSystemException(Exception exception) {

        log.error("系统内部异常", exception);
        return internalError();
    }

    /**
     * 构建统一的服务器错误响应，不向客户端暴露内部异常详情。
     */
    private ResponseEntity<ApiResponse<Void>> internalError() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.<Void>failure(
                        "INTERNAL_ERROR",
                        "系统内部错误"
                ));
    }

    /**
     * 处理请求参数类型错误
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {

        // 获取转换失败的参数名，例如 id、page 或 size
        String parameterName = exception.getName();

        ApiResponse<Void> response = ApiResponse.failure(
                "INVALID_PARAM_TYPE",
                "参数 " + parameterName + " 类型错误，请检查输入"
        );

        // 请求参数不符合接口要求，返回 HTTP 400
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 处理请求体字段校验失败，返回第一条字段错误说明。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException exception) {

        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getDefaultMessage())
                .findFirst()
                .orElse("请求参数不合法");

        return ResponseEntity.badRequest().body(
                ApiResponse.<Void>failure("INVALID_PARAM", message)
        );
    }
    /**
     * 将业务异常转换为对应的 HTTP 状态码和统一响应体。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {

        HttpStatus status = switch (exception.getCode()) {
            case "EVENT_NOT_FOUND",
                 "SESSION_NOT_FOUND",
                 "TICKET_CATEGORY_NOT_FOUND",
                 "ORDER_NOT_FOUND",
                 "EVENT_CATEGORY_NOT_FOUND" -> HttpStatus.NOT_FOUND;

            // 当前资源状态或库存不允许完成操作
            case "EVENT_STATUS_CONFLICT",
                 "SESSION_STATUS_CONFLICT",
                 "TICKET_CATEGORY_DUPLICATE",
                 "EVENT_NOT_ON_SALE",
                 "SESSION_NOT_ON_SALE",
                 "SALE_NOT_STARTED",
                 "SALE_ENDED",
                 "INSUFFICIENT_STOCK",
                 "ORDER_STATUS_CONFLICT",
                 "ORDER_PAYMENT_CONFLICT",
                 "USERNAME_ALREADY_EXISTS",
                 "EVENT_CATEGORY_DISABLED",
                 "REQUEST_PARAM_CONFLICT",
                 "STOCK_NOT_INITIALIZED",
                 "REQUEST_CLOSED",
                 "REQUEST_PROCESSING",
                 "STOCK_WARMUP_CONFLICT",
                 "STOCK_ALREADY_INITIALIZED"-> HttpStatus.CONFLICT;

            case "INVALID_CREDENTIALS" -> HttpStatus.UNAUTHORIZED;
            case "ACCOUNT_DISABLED" -> HttpStatus.FORBIDDEN;

            // 服务暂时无法完成资料加载，客户端可以稍后重试
            case "PURCHASE_INFO_LOADING",
                 "SYSTEM_BUSY"-> HttpStatus.SERVICE_UNAVAILABLE;

            // 当前购买请求超过用户或票档的受理额度。
            // 本次调用尚未预扣库存，提示稍后重试，不能展示成门票售罄。
            case "USER_RATE_LIMITED",
                 "TICKET_BUSY" -> HttpStatus.TOO_MANY_REQUESTS;

            default -> HttpStatus.BAD_REQUEST;
        };

        return ResponseEntity.status(status).body(
                ApiResponse.<Void>failure(
                        exception.getCode(),
                        exception.getMessage()
                )
        );
    }
}