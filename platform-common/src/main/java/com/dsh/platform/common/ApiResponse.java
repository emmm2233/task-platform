package com.dsh.platform.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.ToString;
import org.slf4j.MDC;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统一响应体（四端共用契约）。
 *
 * <pre>
 * {
 *   "code": 0,
 *   "message": "OK",
 *   "data": { },
 *   "traceId": "a1b2c3d4e5f6",
 *   "timestamp": "2026-10-15T13:20:00+08:00"
 * }
 * </pre>
 *
 * <p>约定：
 * <ul>
 *   <li>{@code code} —— 业务码，0 为成功。HTTP 状态码表达协议层语义，业务码表达业务语义，两者并存不冲突。</li>
 *   <li>{@code traceId} —— <b>必须回传前端</b>，用户报障时直接提供，运维凭此定位全链路。</li>
 *   <li>{@code timestamp} —— 服务端时间，前端倒计时以此校准（⛔ 不用客户端本地时间）。</li>
 * </ul>
 *
 * @param <T> 业务数据类型
 */
@Getter
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** 成功时的业务码。 */
    public static final int CODE_OK = 0;

    /** MDC 中链路追踪 id 的键名。 */
    public static final String MDC_TRACE_ID = "traceId";

    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** 业务码。 */
    private final int code;

    /** 用户可读文案（不含堆栈与技术细节）。 */
    private final String message;

    /** 业务数据。 */
    private final T data;

    /** 链路追踪 id。 */
    private final String traceId;

    /** 服务端时间（ISO-8601 带时区，G3）。 */
    private final String timestamp;

    private ApiResponse(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
        this.timestamp = OffsetDateTime.now().format(ISO_OFFSET);
    }

    /** 成功（携带数据），traceId 自动从 MDC 取。 */
    public static <T> ApiResponse<T> ok(T data) {
        return ok(data, currentTraceId());
    }

    /** 成功（携带数据），显式指定 traceId（供测试与跨进程传递）。 */
    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(CODE_OK, "OK", data, traceId);
    }

    /** 成功（无数据）。 */
    public static ApiResponse<Void> ok() {
        return ok(null, currentTraceId());
    }

    /** 失败（用错误码的默认文案）。 */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getDefaultMessage());
    }

    /** 失败（自定义文案 —— 用于"能指导用户下一步"的场景，如抢单失败给出相似任务提示）。 */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null, currentTraceId());
    }

    /** 是否成功。 */
    public boolean isSuccess() {
        return this.code == CODE_OK;
    }

    private static String currentTraceId() {
        String tid = MDC.get(MDC_TRACE_ID);
        return tid == null ? "" : tid;
    }
}
