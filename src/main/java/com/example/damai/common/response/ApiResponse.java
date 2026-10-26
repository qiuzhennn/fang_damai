package com.example.damai.common.response;

/**
 * 统一接口响应。
 *
 * @param <T> 本次响应中业务数据的类型
 */
public class ApiResponse<T> {

    // 业务结果码
    private final String code;

    // 提供给调用方的结果说明
    private final String message;

    // 业务数据；失败时通常为 null
    private final T data;

    // 通过下方工厂方法创建响应，统一成功和失败的构造方式
    private ApiResponse(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 创建成功响应，T 由传入的数据类型推断。
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", "操作成功", data);
    }

    /**
     * 创建失败响应，不携带业务数据。
     */
    public static <T> ApiResponse<T> failure(
            String code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    // getter 用于读取字段，也供 JSON 序列化时使用
    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}