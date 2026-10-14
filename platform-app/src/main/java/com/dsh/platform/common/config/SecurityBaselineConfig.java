package com.dsh.platform.common.config;

import com.dsh.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 安全基线配置（T2 最小可用版）。
 *
 * <p><b>本类在 T5 会被扩展</b>为完整的三层鉴权（路径域 + 权限点 + 数据范围），
 * 见设计 §5.13.3。这里只做两件事：
 * <ol>
 *   <li>放行真正公开的端点（健康检查、OpenAPI 文档），否则应用起来等于全锁死；</li>
 *   <li>其余一律要求认证 —— ⭐ <b>默认拒绝（deny by default）</b>，
 *       新增接口时若忘了配权限点，结果是"访问不了"而不是"裸奔"。
 *       ⛔ 绝不能写成 permitAll 让开发者自己记得加保护。</li>
 * </ol>
 *
 * <p>同时统一 <b>401/403 的响应体形态</b>为 {@link ApiResponse}：
 * 四端（web-user / web-admin / 小程序 / App）的拦截器才能用同一套逻辑处理"登录失效"，
 * 否则拿到的是 Spring Security 默认的 HTML 错误页，前端只能特判。
 */
@Configuration
@RequiredArgsConstructor
public class SecurityBaselineConfig {

    private final ObjectMapper objectMapper;

    /** 无需认证即可访问的路径（⛔ 新增必须经过评审）。 */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/health",
            // 注册/登录/发验证码/换发令牌必须公开（各自自带限流与锁定防护）
            "/api/v1/auth/**",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            // 支付渠道回调：靠验签而非登录态保护，必须放行（T18 实现验签）
            "/api/v1/pay/callback/**"
    };

    @Bean
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                // 纯 Token 鉴权、无 Cookie 会话 → CSRF 不适用（⛔ 但管理端若日后用 Cookie 需重新评估）
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // ⛔ 不要开 httpBasic：
                //   ① 本 API 用 JWT Bearer，不用 Basic 认证；
                //   ② BasicAuthenticationEntryPoint 会**覆盖**下面自定义的 entry point，
                //      走 sendError(401) 导致响应体为空（四端拿不到错误码）；
                //   ③ 它还会带 WWW-Authenticate: Basic，浏览器会弹原生登录框。
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // ⭐ 默认拒绝：未显式放行的都要认证
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> write(res, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, e) -> write(res, HttpStatus.FORBIDDEN))
                );
        return http.build();
    }

    /** 把 401/403 也写成统一响应体，前端才能用一套逻辑处理。 */
    private void write(jakarta.servlet.http.HttpServletResponse response, HttpStatus status)
            throws java.io.IOException {
        var code = status == HttpStatus.UNAUTHORIZED
                ? com.dsh.platform.common.ErrorCode.NOT_LOGIN
                : com.dsh.platform.common.ErrorCode.NO_PERMISSION;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(code));
    }

    /**
     * 密码哈希。BCrypt 自带盐、可调成本因子。
     * ⛔ 不要用 MD5/SHA 系列做密码哈希 —— 它们快，正是暴力破解需要的。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * CORS：两个 Web 工程开发期端口不同，需放行。
     * ⛔ 生产必须收敛为具体域名白名单（不允许 {@code *} 与 credentials 同时出现）。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of(TraceIdHeader.NAME));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    /** 暴露给前端读取的响应头（前端报障时要带上它）。 */
    static final class TraceIdHeader {
        static final String NAME = com.dsh.platform.common.web.TraceIdFilter.HEADER_TRACE_ID;

        private TraceIdHeader() {
        }
    }
}
