package com.smartdrive.common.api;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * requestId 约定：网关与业务服务共用的请求头名、日志 MDC 键名与取值校验。
 * 网关生成或透传，业务服务写入 MDC，日志格式统一输出。
 */
public final class RequestIds {

    /** 请求头名称：网关写入下游请求，业务服务回写响应头 */
    public static final String HEADER = "X-Request-Id";

    /** Logback MDC 键名，对应 logging.pattern.correlation 中的 %X{requestId} */
    public static final String MDC_KEY = "requestId";

    /** 只接受 8~64 位安全字符，避免超长或含特殊字符的值污染日志 */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    /** 客户端传来的值合法则沿用，否则生成新的 UUID */
    public static String resolve(String fromClient) {
        if (fromClient != null && SAFE.matcher(fromClient).matches()) {
            return fromClient;
        }
        return UUID.randomUUID().toString();
    }

    private RequestIds() {
    }
}
