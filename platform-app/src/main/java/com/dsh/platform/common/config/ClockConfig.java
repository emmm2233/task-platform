package com.dsh.platform.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 全局时钟。
 *
 * <p>⛔ 业务代码<b>不得</b>直接调 {@code Instant.now()} / {@code LocalDateTime.now()}：
 * 那样无法测试"30 秒宽限期""T+1 对账""7 天自动验收"这类时间边界，
 * 只能靠 sleep 或改系统时间，既慢又不可靠。
 * 统一注入 {@link Clock}，测试替换成可快进实现。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.system(java.time.ZoneId.of("Asia/Shanghai"));
    }
}
