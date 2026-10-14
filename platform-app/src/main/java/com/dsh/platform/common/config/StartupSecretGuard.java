package com.dsh.platform.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 启动期密钥守卫 —— 让「漏配密钥」变成【起不来】，而不是【静默不安全】。
 *
 * <p>⭐ 为什么必须有它：{@code jwt-secret} 的开发默认值是 58 字节，
 * <b>能通过 TokenService 的「≥ 32 字节」校验</b>。
 * 于是生产环境若忘记注入 {@code PLATFORM_JWT_SECRET}，
 * 应用会<b>正常启动</b>，却用一个人人可见的密钥签 JWT ——
 * 任何人都能伪造 {@code identity=ADMIN} 的令牌。
 *
 * <p>⛔ 这类问题的共同点是<b>失败得静默</b>：不报错、不告警、功能全对。
 * 唯一可靠的治法是在启动时主动拒绝，而不是靠文档提醒或人工检查。
 *
 * <h2>为什么允许 dev/test 用默认值</h2>
 * 本地开发与 CI 需要开箱即用；这里只对<b>非开发 profile</b> 强制校验。
 */
@Slf4j
@Component
public class StartupSecretGuard implements InitializingBean {

    /** 视为开发环境的 profile 前缀；其余一律按生产标准校验。 */
    private static final Set<String> DEV_PROFILES = Set.of("dev", "local", "test", "ci");

    // ⚠️ 这三个字面值必须与 application-dev.yml 里的默认值【完全一致】。
    //    改了一处忘了另一处，守卫就会失效 —— 所以有单测钉住它们。
    static final String DEV_JWT_SECRET = "dev-only-jwt-secret-please-change-in-production-0123456789";
    static final String DEV_KEK = "ZGV2LW9ubHkta2VrLWRvLW5vdC11c2UtaW4tcHJvZCE=";
    static final String DEV_PHONE_SALT = "dev-only-phone-salt";

    private final Environment environment;
    private final String jwtSecret;
    private final String kek;
    private final String phoneSalt;

    public StartupSecretGuard(Environment environment,
                              @Value("${platform.crypto.jwt-secret:}") String jwtSecret,
                              @Value("${platform.crypto.kek:}") String kek,
                              @Value("${platform.crypto.phone-salt:}") String phoneSalt) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.kek = kek;
        this.phoneSalt = phoneSalt;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> active = List.of(environment.getActiveProfiles());
        List<String> problems = violations(active, jwtSecret, kek, phoneSalt);
        if (problems.isEmpty()) {
            if (isDev(active)) {
                log.warn("当前为开发环境（profile={}），使用开发默认密钥。⛔ 生产部署必须注入环境变量。", active);
            }
            return;
        }
        // ⛔ 直接抛，让应用起不来 —— 这是本类存在的全部意义。
        throw new IllegalStateException(
                "检测到生产环境仍在使用【开发默认密钥】，拒绝启动：\n  - "
                        + String.join("\n  - ", problems)
                        + "\n请注入环境变量：PLATFORM_JWT_SECRET / PLATFORM_KEK / PLATFORM_PHONE_SALT");
    }

    /**
     * 纯函数：返回所有违规项（空列表 = 通过）。
     *
     * <p>⭐ 单独抽出来是为了<b>能直接单测</b> —— 不必为了验证规则而启动整个应用。
     */
    static List<String> violations(List<String> activeProfiles, String jwtSecret,
                                   String kek, String phoneSalt) {
        List<String> problems = new ArrayList<>();
        if (isDev(activeProfiles)) {
            return problems;   // 开发环境放行
        }
        if (DEV_JWT_SECRET.equals(jwtSecret)) {
            problems.add("platform.crypto.jwt-secret 仍是开发默认值 —— 该密钥公开可见，可伪造任意身份");
        }
        if (jwtSecret == null || jwtSecret.isBlank()) {
            problems.add("platform.crypto.jwt-secret 未配置");
        }
        if (DEV_KEK.equals(kek)) {
            problems.add("platform.crypto.kek 仍是开发默认值 —— 信封加密形同虚设");
        }
        if (DEV_PHONE_SALT.equals(phoneSalt)) {
            problems.add("platform.crypto.phone-salt 仍是开发默认值 —— 手机号哈希可被彩虹表反查");
        }
        return problems;
    }

    /**
     * 是否【全部】profile 都是开发 profile。
     *
     * <p>⭐ 这里必须用 allMatch 而不是 anyMatch —— 踩过：
     * 最初写成 anyMatch（"只要有一个是 dev 就放行"），
     * 于是 {@code --spring.profiles.active=prod,dev} 可以<b>整体绕过守卫</b>，
     * 而那正是最可能出现的误配形态（为了补配置而把 dev 也带上）。
     *
     * <p>⛔ 安全判定一律取<b>最严</b>的一侧：只要列表里出现任何非开发 profile，
     * 就按生产标准校验。宁可误拦，不可漏放。
     *
     * <p>无激活 profile 时按 dev 处理（本地裸跑 {@code java -jar} 的情形）。
     */
    private static boolean isDev(List<String> activeProfiles) {
        if (activeProfiles == null || activeProfiles.isEmpty()) {
            return true;
        }
        return activeProfiles.stream()
                .map(p -> p.toLowerCase().split("-")[0])
                .allMatch(DEV_PROFILES::contains);
    }
}
