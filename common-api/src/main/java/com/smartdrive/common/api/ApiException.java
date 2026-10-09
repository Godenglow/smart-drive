package com.smartdrive.common.api;

/**
 * 业务异常：由各服务的全局异常处理器统一转成 {@link ApiError}；
 * 不传自定义文案时使用错误码自带文案。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
