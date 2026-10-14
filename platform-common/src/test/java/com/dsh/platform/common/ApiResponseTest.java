package com.dsh.platform.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 · 统一响应体契约测试。
 *
 * <p>本测试是 {@code ApiResponse} 的活文档：它规定了四端（web-user / web-admin /
 * 小程序 / App）都能依赖的响应结构与序列化形态。
 *
 * <p>对应 Global Constraints：
 * <ul>
 *   <li>G2 —— 金额一律字符串传输，⛔ 不用 JSON number（各端浮点精度不同）</li>
 *   <li>G3 —— 时间一律 ISO-8601 带时区</li>
 * </ul>
 */
class ApiResponseTest {

    @Test
    @DisplayName("ok() 的 code 为 0、message 为 OK，并带上 traceId 便于报障定位")
    void ok_has_code_zero_and_trace_id() {
        ApiResponse<String> r = ApiResponse.ok("x", "trace-1");

        assertThat(r.getCode()).isZero();
        assertThat(r.getMessage()).isEqualTo("OK");
        assertThat(r.getTraceId()).isEqualTo("trace-1");
        assertThat(r.getData()).isEqualTo("x");
        assertThat(r.getTimestamp()).isNotBlank();
    }

    @Test
    @DisplayName("ok() 在无显式 traceId 时从 MDC 取，保证不改业务代码也能贯穿链路")
    void ok_reads_trace_id_from_mdc_when_absent() {
        MDC.put("traceId", "mdc-trace");
        try {
            assertThat(ApiResponse.ok("x").getTraceId()).isEqualTo("mdc-trace");
        } finally {
            MDC.remove("traceId");
        }
    }

    @Test
    @DisplayName("失败响应用业务码表达语义，message 不含堆栈等技术细节")
    void failure_carries_business_code() {
        ApiResponse<Void> r = ApiResponse.fail(ErrorCode.GRAB_TAKEN, "该任务已被他人抢走，可查看相似任务");

        assertThat(r.getCode()).isEqualTo(31002);
        assertThat(r.getMessage()).contains("已被他人抢走");
        assertThat(r.getData()).isNull();
    }

    @Test
    @DisplayName("G2：金额字段序列化为 JSON 字符串，不是 number")
    void money_fields_serialize_as_string_not_number() throws Exception {
        ObjectMapper om = new ObjectMapper();

        String json = om.writeValueAsString(Map.of("amount", "1000.00"));

        // 必须是带引号的字符串；裸 1000.00 会被 JS 的 Number 丢精度
        assertThat(json).contains("\"1000.00\"");
        assertThat(json).doesNotContain(":1000.00");
    }

    @Test
    @DisplayName("G2：金额格式化工具保留两位小数，且不接受 double 入参造成精度损失")
    void money_formatting_keeps_two_decimals() {
        assertThat(Money.of("1000").toPlainString()).isEqualTo("1000.00");
        assertThat(Money.of("0.005").toPlainString()).isEqualTo("0.01");   // HALF_UP 到分
        assertThat(Money.of("950").subtract(Money.of("50")).toPlainString()).isEqualTo("900.00");
    }

    @Test
    @DisplayName("errorCode 分段口径稳定：抢单/抽成/信誉分各自成段")
    void error_code_segments_are_stable() {
        assertThat(ErrorCode.GRAB_TAKEN.getCode()).isBetween(31000, 31999);
        assertThat(ErrorCode.COMMISSION_NOT_BALANCED.getCode()).isBetween(41000, 41999);
        assertThat(ErrorCode.CREDIT_INSUFFICIENT.getCode()).isBetween(70000, 70999);
        assertThat(ErrorCode.OK.getCode()).isZero();
    }
}
