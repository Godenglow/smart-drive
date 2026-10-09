package com.smartdrive.common.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * 统一错误码（规格文档 §7.1）：code 即枚举名，随失败响应返回客户端；
 * HTTP 状态码与默认文案跟着错误码一起走，避免各服务各写一套。
 */
public enum ErrorCode {

    INVALID_PARAM(400, "参数不合法"),
    UNAUTHORIZED(401, "未登录或令牌无效"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    PHONE_ALREADY_EXISTS(409, "手机号已注册"),
    INTERNAL_ERROR(500, "服务内部错误");

    private final int httpStatus;
    private final String message;

    ErrorCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public String code() {
        return name();
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }

    /** 按 HTTP 状态码反查错误码，用于把 Spring 自身抛出的异常映射成统一失败响应 */
    public static Optional<ErrorCode> forHttpStatus(int httpStatus) {
        return Arrays.stream(values())
                .filter(errorCode -> errorCode.httpStatus == httpStatus)
                .findFirst();
    }
}
