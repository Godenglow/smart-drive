package com.smartdrive.common.api;

/**
 * 通用响应包装，业务服务统一返回结构；code=0 表示成功。
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
