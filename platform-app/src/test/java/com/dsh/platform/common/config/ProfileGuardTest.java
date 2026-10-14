package com.dsh.platform.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「是不是开发环境」唯一判定出口的单测。
 *
 * <p>⭐ 本类钉住三条<b>真实发生过</b>的失效形态 —— 它们各自都能让一处安全判定静默反向：
 * <ol>
 *   <li><b>空列表按开发放行</b>：生产漏配 profile ⇒ 用公开的开发默认密钥签 JWT；</li>
 *   <li><b>混用非开发 profile 仍按开发放行</b>：最初用 anyMatch ⇒ {@code prod,dev} 可整体绕过守卫；</li>
 *   <li><b>用 {@code contains("dev")} 判字符串</b>：{@code devops} 也算开发环境；
 *       更严重的是读 {@code spring.profiles.active} <b>属性</b>时，
 *       {@code active: dev} + {@code include: prod} 只显示 dev（真实值是 [prod, dev]）。</li>
 * </ol>
 */
class ProfileGuardTest {

    @Test
    @DisplayName("⛔⛔ 空列表 = 无法判定 ⇒ 必须按【非开发】处理（fail-closed）")
    void empty_profiles_are_not_dev() {
        assertThat(ProfileGuard.isDevLike(List.of())).isFalse();
        // 显式转型：ProfileGuard 另有 isDevLike(Environment) 重载，裸 null 会有歧义
        assertThat(ProfileGuard.isDevLike((List<String>) null)).isFalse();
    }

    @Test
    @DisplayName("⭐ 全部都是开发类 profile 才算开发环境")
    void all_dev_profiles_are_dev() {
        assertThat(ProfileGuard.isDevLike(List.of("dev"))).isTrue();
        assertThat(ProfileGuard.isDevLike(List.of("local"))).isTrue();
        assertThat(ProfileGuard.isDevLike(List.of("test"))).isTrue();
        assertThat(ProfileGuard.isDevLike(List.of("ci"))).isTrue();
        // 取第一个 '-' 之前的部分：dev-docker / local-mysql 也是开发环境
        assertThat(ProfileGuard.isDevLike(List.of("dev-docker"))).isTrue();
        assertThat(ProfileGuard.isDevLike(List.of("local-mysql"))).isTrue();
        assertThat(ProfileGuard.isDevLike(List.of("dev", "dev-docker"))).isTrue();
    }

    @Test
    @DisplayName("⭐⭐ 只要有一个非开发 profile 就按生产校验（anyMatch 曾导致整体绕过）")
    void any_non_dev_profile_wins() {
        assertThat(ProfileGuard.isDevLike(List.of("dev", "prod"))).isFalse();
        assertThat(ProfileGuard.isDevLike(List.of("prod", "dev"))).isFalse();
        assertThat(ProfileGuard.isDevLike(List.of("dev", "docker"))).isFalse();
        assertThat(ProfileGuard.isDevLike(List.of("dev", "staging"))).isFalse();
    }

    @Test
    @DisplayName("⛔ 不用 contains 判字符串：devops / development 不是开发 profile")
    void substring_semantics_are_gone() {
        assertThat(ProfileGuard.isDevLike(List.of("devops"))).isFalse();
        assertThat(ProfileGuard.isDevLike(List.of("development"))).isFalse();
        assertThat(ProfileGuard.isDevLike(List.of("redev"))).isFalse();
    }

    @Test
    @DisplayName("大小写不敏感")
    void case_insensitive() {
        assertThat(ProfileGuard.isDevLike(List.of("DEV"))).isTrue();
        assertThat(ProfileGuard.isDevLike(List.of("Dev-Docker"))).isTrue();
    }
}
