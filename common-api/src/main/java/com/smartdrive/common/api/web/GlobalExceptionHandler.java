package com.smartdrive.common.api.web;

import com.smartdrive.common.api.ApiError;
import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.RequestIds;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理（规格文档 §7.1）：把业务异常和常见框架异常统一转成
 * {@code { code, message, requestId }} 失败响应；500 只记日志，不把堆栈返回客户端。
 * 各业务服务通过扫描 com.smartdrive.common.api 生效。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        ErrorCode errorCode = ex.errorCode();
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiError.of(errorCode, ex.getMessage(), RequestIds.current()));
    }

    /** @Valid 校验请求体失败：逐字段列出原因，便于前端直接展示 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return invalidParam(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return invalidParam("请求体缺失或 JSON 格式错误");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return invalidParam("参数 " + ex.getName() + " 类型不正确");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex) {
        return invalidParam("缺少必填参数 " + ex.getParameterName());
    }

    /** 兜底：Spring 自身的 HTTP 语义异常（如路径不存在 404、方法不支持 405）按原状态码返回，其余记日志后返回 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            int status = errorResponse.getStatusCode().value();
            Optional<ErrorCode> known = ErrorCode.forHttpStatus(status);
            if (known.isPresent() && status < 500) {
                ErrorCode errorCode = known.get();
                return ResponseEntity.status(status)
                        .body(ApiError.of(errorCode, errorCode.message(), RequestIds.current()));
            }
        }
        log.error("未处理异常", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message(), RequestIds.current()));
    }

    private ResponseEntity<ApiError> invalidParam(String message) {
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.httpStatus())
                .body(ApiError.of(ErrorCode.INVALID_PARAM, message, RequestIds.current()));
    }
}
