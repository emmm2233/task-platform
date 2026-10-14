package com.dsh.platform.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
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
    @DisplayName("⛔⛔ 无激活 profile（生产漏配 profile）必须按【生产】处理，不能按 dev 放行")
    void no_profile_is_treated_as_production() {
        // 踩过：原来这里断言"空列表按 dev 放行（裸跑 java -jar 的情形）"——
        // 但 application.yml 里已写死 spring.profiles.active: dev，
        // 所以裸跑 java -jar 拿到的是 [dev]，【不会】命中空列表。
        // 真正会命中空列表的只有一种情况：生产环境漏配 profile
        // （外部配置没写 active，或 SPRING_PROFILES_ACTIVE= 空串）。
        // 按 dev 放行的后果：jwt-secret 取 application-dev.yml 里那个公开的默认值
        // ⇒ 任何人都能签出 identity=ADMIN 的令牌。
        assertThat(StartupSecretGuard.violations(List.of(),
                StartupSecretGuard.DEV_JWT_SECRET, StartupSecretGuard.DEV_KEK,
                StartupSecretGuard.DEV_PHONE_SALT))
                .as("无法判定环境时必须取最严")
                .hasSize(3);
    }

    @Test
    @DisplayName("⭐⭐ 默认密钥字面量必须真的与 application-dev.yml 一致（读 yml，不是拿常量自比）")
    void dev_default_literals_match_application_dev_yml() throws Exception {
        // ⚠️ 本条修复前的写法是把 StartupSecretGuard.DEV_* 常量【自己】当参数传进 violations()，
        //    从未读 application-dev.yml —— 于是"改了 yml 却忘了改常量"
        //    会让守卫静默失效，而 8 个用例依旧全绿（正是本类要防的那类问题）。
        String yml = new ClassPathResource("application-dev.yml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(yml)
                .as("application-dev.yml 的 KEK 默认值必须与 StartupSecretGuard.DEV_KEK 一致")
                .contains("${PLATFORM_KEK:" + StartupSecretGuard.DEV_KEK + "}");
        assertThat(yml)
                .as("application-dev.yml 的 jwt-secret 默认值必须与 StartupSecretGuard.DEV_JWT_SECRET 一致")
                .contains("${PLATFORM_JWT_SECRET:" + StartupSecretGuard.DEV_JWT_SECRET + "}");
        assertThat(yml)
                .as("application-dev.yml 的 phone-salt 默认值必须与 StartupSecretGuard.DEV_PHONE_SALT 一致")
                .contains("${PLATFORM_PHONE_SALT:" + StartupSecretGuard.DEV_PHONE_SALT + "}");
    }
}
