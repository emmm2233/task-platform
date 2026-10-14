package com.dsh.platform.common;

import lombok.Getter;

import java.util.Map;

/**
 * 业务异常。
 *
 * <p>所有可预期的业务失败都用它抛出，由 {@code GlobalExceptionHandler} 统一转成
 * {@link ApiResponse}。⛔ 业务代码不得直接返回错误码，也不得抛裸 {@code RuntimeException}
 * —— 后者会被当成 500，前端无法区分"业务拒绝"与"系统故障"。
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务错误码。 */
    private final ErrorCode errorCode;

    /** 文案占位参数，如 {@code {count: 3}} → "你已有 3 个进行中任务"。 */
    private final transient Map<String, Object> args;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), Map.of());
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public BizException(ErrorCode errorCode, String message, Map<String, Object> args) {
        super(message);
        this.errorCode = errorCode;
        this.args = args == null ? Map.of() : Map.copyOf(args);
    }

    /**
     * 业务异常不需要堆栈 —— 它是可预期流程分支，抓堆栈只会拖慢并且污染日志。
     * ⛔ 真正的系统故障请用其他异常类型，那些需要完整堆栈。
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

    public int getCode() {
        return errorCode.getCode();
    }
}
