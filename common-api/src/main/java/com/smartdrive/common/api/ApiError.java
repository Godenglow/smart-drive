package com.smartdrive.common.api;

/**
 * 失败响应体（规格文档 §7.1）：{@code { code, message, requestId }}；
 * 成功响应仍用 {@link Result} 包装。
 */
public record ApiError(String code, String message, String requestId) {

    public static ApiError of(ErrorCode errorCode, String message, String requestId) {
        return new ApiError(errorCode.code(), message, requestId);
    }
}
