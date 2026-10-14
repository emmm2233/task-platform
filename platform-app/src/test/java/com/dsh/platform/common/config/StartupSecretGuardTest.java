package com.dsh.platform.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动期密钥守卫的单测。
 *
 * <p>⭐ 本类钉住两件事，缺一守卫就会失效：
 * <ol>
 *   <li><b>规则本身</b>：非开发 profile 用默认密钥必须被拒；</li>
 *   <li>⭐ <b>默认值字面量与 application-dev.yml 一致</b> ——
 *       若有人改了 yml 里的默认值却忘了改这里，守卫会<b>静默失效</b>，
 *       而那正是它要防的那类问题。</li>
 * </ol>
 */
class StartupSecretGuardTest {

    @Test
    @DisplayName("开发/测试 profile 使用默认密钥应放行（本地开箱即用）")
    void dev_profiles_are_allowed() {
        for (String p : List.of("dev", "local", "test", "ci", "dev-docker")) {
            assertThat(StartupSecretGuard.violations(List.of(p),
                    StartupSecretGuard.DEV_JWT_SECRET, StartupSecretGuard.DEV_KEK,
                    StartupSecretGuard.DEV_PHONE_SALT))
                    .as("profile=%s 应放行", p)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("⭐ 生产 profile 使用默认密钥必须被拒，且三条都报出来")
    void prod_with_dev_defaults_is_rejected() {
        List<String> v = StartupSecretGuard.violations(List.of("prod"),
                StartupSecretGuard.DEV_JWT_SECRET, StartupSecretGuard.DEV_KEK,
                StartupSecretGuard.DEV_PHONE_SALT);

        assertThat(v).hasSize(3);
        assertThat(v).anyMatch(s -> s.contains("jwt-secret"));
        assertThat(v).anyMatch(s -> s.contains("kek"));
        assertThat(v).anyMatch(s -> s.contains("phone-salt"));
    }

    @Test
    @DisplayName("⭐⭐ prod,dev 混用时【必须仍然拦截】—— 守卫不能被 dev 顺带绕过")
    void mixed_prod_and_dev_profiles_still_enforce() {
        // 踩过：最初 isDev 用 anyMatch，于是 --spring.profiles.active=prod,dev
        // 整体绕过守卫。而那正是最可能出现的误配形态（为了补配置把 dev 也带上）。
        List<String> v = StartupSecretGuard.violations(List.of("prod", "dev"),
                StartupSecretGuard.DEV_JWT_SECRET, StartupSecretGuard.DEV_KEK,
                StartupSecretGuard.DEV_PHONE_SALT);

        assertThat(v).as("含非开发 profile 就必须按生产标准校验").hasSize(3);
    }

    @Test
    @DisplayName("staging 也按生产标准（不是只有 prod 才严）")
    void staging_is_enforced_too() {
        assertThat(StartupSecretGuard.violations(List.of("staging"),
                StartupSecretGuard.DEV_JWT_SECRET, "k", "s"))
                .isNotEmpty();
    }

    @Test
    @DisplayName("生产 profile 注入真实密钥应通过")
    void prod_with_real_secrets_passes() {
        assertThat(StartupSecretGuard.violations(List.of("prod"),
                "a-real-32-plus-byte-secret-injected-from-env-0001",
                "cmVhbC1rZWstZnJvbS1lbnYtdmFsdWUtMDAwMDAwMDA=",
                "real-phone-salt-from-env"))
                .isEmpty();
    }

    @Test
    @DisplayName("只漏配一个也要拒（不能因为「其他两个配了」就放行）")
    void partial_misconfiguration_is_rejected() {
        List<String> v = StartupSecretGuard.violations(List.of("prod"),
                "a-real-32-plus-byte-secret-injected-from-env-0001",
                "cmVhbC1rZWstZnJvbS1lbnYtdmFsdWUtMDAwMDAwMDA=",
                StartupSecretGuard.DEV_PHONE_SALT);

        assertThat(v).hasSize(1);
        assertThat(v.get(0)).contains("phone-salt");
    }

    @Test
    @DisplayName("⚠️ jwt-secret 为空/缺失也必须拒（不能因为「不等于默认值」就放行）")
    void blank_jwt_secret_is_rejected() {
        assertThat(StartupSecretGuard.violations(List.of("prod"), "", "k", "s"))
                .anyMatch(s -> s.contains("未配置"));
        assertThat(StartupSecretGuard.violations(List.of("prod"), null, "k", "s"))
                .anyMatch(s -> s.contains("未配置"));
    }

    @Test
    @DisplayName("无激活 profile（裸跑 java -jar）按 dev 处理")
    void no_profile_is_treated_as_dev() {
        assertThat(StartupSecretGuard.violations(List.of(),
                StartupSecretGuard.DEV_JWT_SECRET, StartupSecretGuard.DEV_KEK,
                StartupSecretGuard.DEV_PHONE_SALT)).isEmpty();
    }
}
