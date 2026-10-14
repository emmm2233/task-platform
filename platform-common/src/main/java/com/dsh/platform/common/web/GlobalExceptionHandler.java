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

    /**
     * ⭐ 方法级鉴权失败（{@code @PreAuthorize} 拒绝）必须转成 <b>403</b>，不能被兜底处理器吞掉。
     *
     * <p>为什么必须显式声明：下面那个 {@code @ExceptionHandler(Exception.class)} 会
     * <b>抢先捕获</b> {@code AccessDeniedException}，于是方法级鉴权失败会被转成 500/系统繁忙，
     * 而 Spring Security 的 {@code ExceptionTranslationFilter}（负责产出 403）根本轮不到执行。
     *
     * <p>表现极具误导性：接口"能访问"（HTTP 200），前端只看到"系统繁忙"，
     * 既不知道是权限问题，也无从排查。本项目的 T6 测试就是这么发现它的
     * —— 断言 403，实际拿到 200。
     *
     * <p>⚠️ 路径域（过滤链）的拒绝走 {@code accessDeniedHandler}，不经过这里；
     * 两条路径都要能产出 403，只修一边是不够的。
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException e) {
        log.warn("方法级鉴权拒绝: {}", e.getMessage());
        return org.springframework.http.ResponseEntity
                .status(org.springframework.http.HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(ErrorCode.NO_PERMISSION));
    }

    /** 兜底：真正的系统故障，记完整堆栈但⛔ 不把细节返回给客户端。 */
    /**
     * 未映射的路径 → 404。
     *
     * <p>⭐ 这是 T6 那个 AccessDeniedException → 200 缺陷的同一类，且危害更大：
     * 兜底的 @ExceptionHandler(Exception.class) 会把 NoResourceFoundException
     * 也变成 200 + "系统繁忙"。
     *
     * <p>实测（真实启动 + 打接口）：GET /api/v1/tasks 返回 HTTP 200 与
     * {"code":10999,"message":"系统繁忙，请稍后再试"}，而当时根本没有这个接口。
     *
     * <p>为什么必须显式处理：
     * - 排查方向被带偏：controller 注册失败会表现为"系统繁忙"而非"路由不存在"；
     * - 前端无法区分"接口不存在（发版不一致）"与"服务端故障"；
     * - 监控无法按 404 率发现前后端路由不一致。
     *
     * <p>⚠️ 只对【真正未映射】的路径生效；已映射但被安全规则拦下的走 401/403。
     */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
        log.warn("未映射的请求路径：{}", e.getMessage());
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("系统异常", e);
        return ApiResponse.fail(ErrorCode.SYSTEM_BUSY);
    }

    private String describe(FieldError fe) {
        return fe.getField() + " " + fe.getDefaultMessage();
    }
}
