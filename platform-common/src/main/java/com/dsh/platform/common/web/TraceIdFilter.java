package com.dsh.platform.common.web;

import com.dsh.platform.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器 —— 让 {@link ApiResponse#getTraceId()} 真正有值。
 *
 * <p>规则：
 * <ul>
 *   <li>优先沿用调用方传来的 {@code X-Trace-Id}（网关 / 上游服务 / 压测脚本可注入，
 *       便于把一次端到端操作串成一条链）。</li>
 *   <li>没有则生成短 id（16 位十六进制，足够唯一且日志里不占地方）。</li>
 *   <li>写入 MDC 与响应头，并<b>无论成败都清理 MDC</b> —— 不清理会在线程复用时串味。</li>
 * </ul>
 *
 * <p>⚠️ 必须排在安全过滤器<b>之前</b>：401/403 也应当带上 traceId，
 * 否则用户报「我访问不了」时，运维拿不到任何线索。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(ApiResponse.MDC_TRACE_ID, traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(ApiResponse.MDC_TRACE_ID);
        }
    }
}
