package com.dsh.platform.common.config;

import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 「当前是不是开发环境」的<b>唯一判定出口</b>。
 *
 * <h2>为什么必须收敛到一个地方</h2>
 * 修复前同一个问题有三份实现，而且语义互不相同：
 * <pre>
 *   StartupSecretGuard   environment.getActiveProfiles()      ✅ 取真值（但空列表按 dev 放行 ✗）
 *   SecurityBaselineConfig  @Value("${spring.profiles.active:dev}")   ✗ 取的是【配置属性】
 *   CryptoConfig         @Value(...) 且用 profile.contains("dev")     ✗ 同上，且 devops 也算 dev
 * </pre>
 * 「取配置属性」这一路是可被绕过的（独立审查用最小 Spring Boot 3.2.5 探针实测）：
 * <pre>
 *   application.yml:  active: dev  +  include: prod
 *   →  environment.getActiveProfiles() = [prod, dev]        （真值）
 *   →  @Value("${spring.profiles.active:dev}") = [dev]      （⚠️ 只看到 active，看不到 include）
 * </pre>
 * 后果是同一进程里两套<b>互相矛盾</b>的结论：Swagger 匿名开放（按 dev 放行），
 * 同时用开发默认 KEK 加密生产数据（按 dev 跳过校验）。
 * {@code spring.profiles.include} / {@code group} / {@code @ActiveProfiles} /
 * {@code setAdditionalProfiles} 都能触发，而 {@code --spring.profiles.active=} 反而正常 ——
 * 也就是说它专门在「配置方式不是命令行」的场景下失效。
 *
 * <h2>两条硬规则</h2>
 * <ol>
 *   <li>只认 {@link Environment#getActiveProfiles()}，⛔ 不读 {@code spring.profiles.active} 属性。</li>
 *   <li><b>无法判定时按最严</b>：空列表返回 {@code false}（非开发）。
 *       裸跑 {@code java -jar} 不会命中这一支 —— {@code application.yml} 里写死了
 *       {@code spring.profiles.active: dev}；真正会命中空列表的是
 *       <b>生产环境漏配 profile</b>，那正是必须按生产校验的场景。</li>
 *   <li>取最严一侧：<b>全部</b> profile 都是开发类才算开发。
 *       {@code dev,prod} / {@code prod,dev} 一律按生产校验
 *       （踩过：最初用 anyMatch，带上 dev 就能整体绕过守卫）。</li>
 * </ol>
 */
public final class ProfileGuard {

    /**
     * 视为开发环境的 profile（比较时取第一个 {@code -} 之前的部分，
     * 因此 {@code dev-docker} / {@code local-mysql} 也是开发环境）。
     */
    private static final Set<String> DEV_PROFILES = Set.of("dev", "local", "test", "ci");

    private ProfileGuard() {
    }

    /** 真实激活的 profile 列表。 */
    public static List<String> activeProfiles(Environment environment) {
        return List.of(environment.getActiveProfiles());
    }

    /** 是否处于开发环境。空列表 ⇒ {@code false}（按最严）。 */
    public static boolean isDevLike(Environment environment) {
        return isDevLike(activeProfiles(environment));
    }

    /**
     * 是否处于开发环境。
     *
     * @param activeProfiles 真实激活的 profile；<b>空列表视为非开发</b>
     */
    public static boolean isDevLike(List<String> activeProfiles) {
        if (activeProfiles == null || activeProfiles.isEmpty()) {
            return false;
        }
        return activeProfiles.stream()
                .filter(Objects::nonNull)
                .map(p -> p.toLowerCase().split("-")[0])
                .allMatch(DEV_PROFILES::contains);
    }
}
