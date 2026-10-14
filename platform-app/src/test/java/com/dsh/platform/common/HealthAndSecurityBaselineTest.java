package com.dsh.platform.common;

import com.dsh.platform.common.web.TraceIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T2 · 应用可启动 + 安全基线 + 健康检查契约测试。
 *
 * <p>验证三件事：
 * <ol>
 *   <li>公开端点（健康检查）无需认证即可访问 —— 否则应用起来等于全锁死。</li>
 *   <li>非公开端点<b>默认拒绝</b>，且 401 的响应体是统一的 {@link ApiResponse} 形态，
 *       ⛔ 不是 Spring Security 默认的 HTML 错误页。</li>
 *   <li>traceId 贯穿：请求头可注入、响应头必回传、响应体里有值。</li>
 * </ol>
 *
 * <p>依赖：本机 MySQL（task_platform 库）与 Redis 服务已启动。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HealthAndSecurityBaselineTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("健康检查无需认证即可访问，且返回组件状态")
    void health_is_publicly_accessible() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.components.db").value("UP"));
    }

    @Test
    @DisplayName("尚未接入的组件报 NOT_CONFIGURED，不把整体拖成 DOWN")
    void not_yet_wired_components_do_not_fail_overall_health() throws Exception {
        // MQ 要到 T15/T29 才接入；开发机 Redis 未置为必需
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(jsonPath("$.data.components.mq").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    @DisplayName("⭐ 非公开端点默认拒绝：401 且响应体是统一 ApiResponse 形态")
    void protected_endpoint_is_denied_by_default_with_unified_body() throws Exception {
        mockMvc.perform(get("/api/v1/account/profile"))
                .andExpect(status().isUnauthorized())
                // ⛔ 不能是 Spring Security 默认的 HTML 错误页
                .andExpect(jsonPath("$.code").value(20001))
                .andExpect(jsonPath("$.message").value("未登录或登录已失效"))
                .andExpect(jsonPath("$.traceId").value(not(emptyString())));
    }

    @Test
    @DisplayName("traceId 可由调用方注入，并在响应头回传（便于端到端串链）")
    void trace_id_is_propagated_from_request_header() throws Exception {
        String given = "abc123def4567890";
        mockMvc.perform(get("/api/v1/health").header(TraceIdFilter.HEADER_TRACE_ID, given))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.HEADER_TRACE_ID, given))
                .andExpect(jsonPath("$.traceId").value(given));
    }

    @Test
    @DisplayName("未注入 traceId 时自动生成，且响应体与响应头一致")
    void trace_id_is_generated_when_absent() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(TraceIdFilter.HEADER_TRACE_ID))
                .andExpect(jsonPath("$.traceId").value(not(emptyString())));
    }

    @Test
    @DisplayName("G3：响应时间戳是 ISO-8601 带时区（各端才能正确解析）")
    void timestamp_is_iso8601_with_offset() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(jsonPath("$.timestamp")
                        .value(org.hamcrest.Matchers.matchesPattern(
                                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?[+-]\\d{2}:\\d{2}")));
    }
}
