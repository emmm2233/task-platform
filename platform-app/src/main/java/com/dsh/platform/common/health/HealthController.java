package com.dsh.platform.common.health;

import com.dsh.platform.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查（K8s 探针 + 运维自助诊断）。
 *
 * <p>与 {@code /actuator/health} 的区别：本接口<b>按依赖的"必需性"决定整体状态</b>。
 * 尚未接入的组件（如未配置 Redis 的开发机）报 {@code NOT_CONFIGURED}，
 * <b>不会</b>把整体拖成 DOWN —— 否则本地开发永远起不来。
 *
 * <p>必需性由 {@code platform.env.redis-required} / {@code platform.env.mq-required} 控制：
 * T4 接入认证后 redis 转 true，T15/T29 接入 MQ 后 mq 转 true。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    private static final String UP = "UP";
    private static final String DOWN = "DOWN";
    private static final String NOT_CONFIGURED = "NOT_CONFIGURED";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    @Value("${platform.env.redis-required:false}")
    private boolean redisRequired;

    @Value("${platform.env.mq-required:false}")
    private boolean mqRequired;

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("db", checkDb());
        components.put("redis", checkRedis());
        components.put("mq", checkMq());

        boolean overallUp = isRequiredUp("db", components.get("db"))
                && isRequiredUp("redis", components.get("redis"))
                && isRequiredUp("mq", components.get("mq"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", overallUp ? UP : DOWN);
        body.put("components", components);
        return ApiResponse.ok(body);
    }

    private String checkDb() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return UP;
        } catch (Exception e) {
            log.error("数据库探活失败", e);
            return DOWN;
        }
    }

    private String checkRedis() {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return NOT_CONFIGURED;
        }
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection ->
                    connection.ping());
            return UP;
        } catch (Exception e) {
            log.warn("Redis 探活失败: {}", e.getMessage());
            return DOWN;
        }
    }

    private String checkMq() {
        // MQ 在 T15/T29 接入；在此之前恒为 NOT_CONFIGURED
        return NOT_CONFIGURED;
    }

    private boolean isRequiredUp(String name, Object state) {
        boolean required = switch (name) {
            case "db" -> true;
            case "redis" -> redisRequired;
            case "mq" -> mqRequired;
            default -> false;
        };
        if (!required) {
            return true;   // 非必需组件不影响整体状态
        }
        return UP.equals(state);
    }
}
