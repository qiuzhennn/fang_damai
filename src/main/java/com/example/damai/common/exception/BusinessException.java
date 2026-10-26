package com.example.damai.common.exception;

/**
 * 业务异常处理。
 */
public class BusinessException extends RuntimeException {

    // 业务错误码，供调用方区分不同错误
    private final String code;

    public BusinessException(String code, String message) {
        // 将错误说明交给父类保存，后续通过 getMessage() 获取
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}