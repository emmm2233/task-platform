package com.dsh.platform.common.config;

import com.dsh.platform.common.crypto.EnvelopeCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 加密能力装配。
 *
 * <p>⛔ 生产环境必须用 {@code PLATFORM_KEK} 环境变量注入，不得使用默认值 ——
 * 默认值只为了本地开发能起来。启动时若检测到"非 dev 环境 + 默认 KEK"会直接拒绝启动。
 */
@Slf4j
@Configuration
public class CryptoConfig {

    /** 仅本地开发使用的固定 KEK（32 字节）。⛔ 生产绝不可用。 */
    private static final String DEV_KEK = "ZGV2LW9ubHkta2VrLWRvLW5vdC11c2UtaW4tcHJvZCE=";

    private static final String DEV_SALT = "dev-only-phone-salt";

    @Value("${platform.crypto.kek:" + DEV_KEK + "}")
    private String kek;

    @Value("${platform.crypto.phone-salt:" + DEV_SALT + "}")
    private String phoneSalt;

    /** ⭐ 判定"是不是开发环境"只认它，⛔ 不读 spring.profiles.active 属性。 */
    private final Environment environment;

    public CryptoConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public EnvelopeCipher envelopeCipher() {
        // ⛔⛔ 这里曾写 `@Value("${spring.profiles.active:dev}")` + `profile.contains("dev")`，
        //    两个毛病：
        //    ① 读的是【配置属性】而不是真实激活的 profile ——
        //       application.yml 写 active: dev 再配 include: prod 时，
        //       真实 profile 是 [prod, dev]，而属性只显示 [dev]
        //       ⇒ 生产用【开发默认 KEK】加密数据，且不报错（独立审查探针实测）；
        //    ② contains("dev") 会把 devops / development 也算成开发环境。
        //    ⭐ 现在与 StartupSecretGuard 共用 ProfileGuard：取真实值 + 全 dev 才算 dev + 空列表按最严。
        if (DEV_KEK.equals(kek) && !ProfileGuard.isDevLike(environment)) {
            // ⛔ 宁可起不来，也不能用开发密钥加密生产数据
            throw new IllegalStateException(
                    "检测到非开发环境仍在使用默认 KEK。请通过 PLATFORM_KEK 注入（生产环境应来自 Vault/KMS）。");
        }
        if (DEV_KEK.equals(kek)) {
            log.warn("⚠️ 正在使用开发用 KEK，仅限本地开发。生产请注入 PLATFORM_KEK。");
        }
        return new EnvelopeCipher(kek);
    }

    /**
     * 手机号盲索引。
     *
     * <p>为什么需要它：手机号要能"按键精确查找"（登录、去重），
     * 但 ⛔ 不能明文建索引（一旦库泄露就是完整的手机号清单）。
     * 用 HMAC-SHA256(手机号, 盐) 得到确定性哈希：同一手机号恒定映射同一哈希，
     * 可建唯一索引，且**无法被彩虹表反查**（盐不泄露的前提下）。
     */
    @Bean
    public PhoneHasher phoneHasher() {
        return new PhoneHasher(phoneSalt);
    }

    /** 手机号/身份证号的确定性盲索引计算器。 */
    public static class PhoneHasher {

        private final byte[] salt;

        PhoneHasher(String salt) {
            this.salt = salt.getBytes(StandardCharsets.UTF_8);
        }

        /** 返回 64 位小写十六进制。 */
        public String hash(String value) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(salt, "HmacSHA256"));
                return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException("盲索引计算失败", e);
            }
        }
    }

    /** 供测试与工具生成一个新的 32 字节 KEK。 */
    public static String generateKek() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }
}
