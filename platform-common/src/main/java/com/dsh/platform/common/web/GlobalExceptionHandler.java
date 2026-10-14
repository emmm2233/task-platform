package com.dsh.platform.common.web;

import com.dsh.platform.common.ApiResponse;
import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把各类异常统一收敛成 {@link ApiResponse}。
 *
 * <p>三条纪律：
 * <ol>
 *   <li>⛔ 生产环境<b>不得把堆栈写入响应</b>（泄露 SQL / 表名 / 框架版本 / 内网 IP）。</li>
 *   <li>{@link BizException} 是<b>可预期流程分支</b>，按 WARN 记一行即可，不打堆栈。</li>
 *   <li>其余异常是<b>系统故障</b>，按 ERROR 记完整堆栈并返回 {@code 10999}，
 *       同时带上 traceId 便于用户报障。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 可预期的业务失败。 */
    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException e) {
        // 不打堆栈：业务异常是流程分支，不是故障
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return ApiResponse.fail(e.getErrorCode(), e.getMessage());
    }

    /** @Valid 校验失败（请求体）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败: {}", detail);
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, detail.isEmpty()
                ? ErrorCode.PARAM_INVALID.getDefaultMessage() : detail);
    }

    /** @Valid 校验失败（表单绑定）。 */
    @ExceptionHandler(BindException.class)
    public ApiResponse<Void> handleBind(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining("；"));
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, detail);
    }

    /** @Validated 在方法参数上的约束失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Void> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("；"));
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, detail);
    }

    /** 请求体不可读 / JSON 格式错误。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体不可读: {}", e.getMessage());
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, "请求体格式错误");
    }

    /** 缺少必需的请求头（如 Idempotency-Key）。 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ApiResponse<Void> handleMissingHeader(MissingRequestHeaderException e) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, "缺少必需的请求头：" + e.getHeaderName());
    }

    /** 缺少必需的查询参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ApiResponse<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, "缺少必需的参数：" + e.getParameterName());
    }

    /** 兜底：真正的系统故障，记完整堆栈但⛔ 不把细节返回给客户端。 */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("系统异常", e);
        return ApiResponse.fail(ErrorCode.SYSTEM_BUSY);
    }

    private String describe(FieldError fe) {
        return fe.getField() + " " + fe.getDefaultMessage();
    }
}
