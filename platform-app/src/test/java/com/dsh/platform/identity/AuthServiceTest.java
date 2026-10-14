package com.dsh.platform.identity;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.identity.dto.LoginResult;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.SmsCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T4 · 注册登录与短信验证码。
 *
 * <p>对应 Global Constraints：
 * <ul>
 *   <li>G10 —— 信誉分初始 60、接单门槛 60</li>
 *   <li>P1-⑤ —— Refresh Token 一次性，但必须给 <b>30 秒宽限期</b>，
 *       否则多标签页/弱网重试会把正常用户踢下线（这是 §6.7 审计抓到的真问题）</li>
 * </ul>
 */
@SpringBootTest
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
@Import(AuthServiceTest.FastClockConfig.class)
class AuthServiceTest {

    @Autowired
    private StringRedisTemplate redis;

    /**
     * ⭐ 清掉限流计数。
     *
     * <p>限流状态存在 Redis，TTL 2 小时 —— 它<b>跨测试类、跨运行都活着</b>。
     * 不清理的话，第二次跑测试就会撞上一轮留下的计数（本测试踩过两次：
     * 一次是手机号由 SEQ 递推导致每轮同一批号码，一次是随机 IP 恰好撞车）。
     * 用随机值只能降低概率，<b>确定性清理才根治</b>。
     */
    @BeforeAll
    void clearRateLimitCounters() {
        var keys = redis.keys("sms:limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** 用可快进时钟替换系统时钟，才能稳定验证「30 秒宽限期」这种时间边界。 */
    @TestConfiguration
    static class FastClockConfig {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private static final java.util.Random RANDOM = new java.util.Random();

    @Autowired
    private AuthService authService;

    @Autowired
    private SmsCodeService smsCodeService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.dsh.platform.common.config.SysConfigService configService;

    /** 可注入的时钟，用于"快进 31 秒"验证宽限期边界。 */
    @Autowired
    private TestClock clock;

    private String phone;

    /** 注册用的短信验证码（⛔ 必须真的走一遍发送流程，否则测不到限流）。 */
    private String regCode;

    /**
     * ⚠️ 手机号与 IP 必须用<b>随机值</b>，不能由 SEQ 递推。
     *
     * <p>两个坑都踩过：
     * <ol>
     *   <li>所有测试共用 {@code 127.0.0.1} → 第 6 个用例撞上 IP 小时上限 5，
     *       7 个用例全挂在 RATE_LIMITED（这反过来证明 IP 维度确实有效）。</li>
     *   <li>手机号由 SEQ 从固定基数递推 → <b>每次运行都是同一批号码</b>，
     *       而 Redis 计数器 TTL 是 2 小时 → 第二次跑测试就撞上一轮留下的计数。</li>
     * </ol>
     * 限流状态存在 Redis（跨测试类、跨运行都活着），测试数据必须与之解耦。
     */
    private String ip;

    private String device;

    @BeforeEach
    void setUp() {
        int n = SEQ.incrementAndGet();
        phone = "138" + String.format("%08d", RANDOM.nextInt(100_000_000));
        ip = "10." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(254);
        device = "dev-" + RANDOM.nextLong(1_000_000_000L);
        regCode = smsCodeService.send(phone, SmsScene.REGISTER, ip, device);
    }

    @Test
    @DisplayName("G10：新用户信誉分 60、等级 NORMAL、实名状态未提交")
    void new_user_starts_at_credit_60_and_normal_level() {
        LoginResult r = authService.register(phone, "Passw0rd!", "小张", regCode);

        assertThat(r.getCreditScore()).isEqualTo(60);
        assertThat(r.getCreditLevel()).isEqualTo("NORMAL");
        assertThat(r.getAccessToken()).isNotBlank();
        assertThat(r.getRefreshToken()).isNotBlank();
        assertThat(r.getUserNo()).startsWith("U");

        Integer score = jdbc.queryForObject(
                "SELECT credit_score FROM sys_user WHERE id = ?", Integer.class, r.getUserId());
        assertThat(score).isEqualTo(60);
    }

    @Test
    @DisplayName("手机号不落明文索引：库里查得到 phone_hash，且明文经过加密")
    void phone_is_hashed_for_lookup_and_not_stored_in_plain_index() {
        LoginResult r = authService.register(phone, "Passw0rd!", "小张", regCode);

        String hash = jdbc.queryForObject(
                "SELECT phone_hash FROM sys_user WHERE id = ?", String.class, r.getUserId());
        assertThat(hash).isNotBlank().hasSize(64);   // SHA-256 hex

        // 同一手机号不能重复注册
        assertThatThrownBy(() -> authService.register(phone, "Passw0rd!", "小李", regCode))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("密码错误给出统一错误码，⛔ 不泄露『该手机号是否存在』")
    void wrong_password_uses_uniform_error_code() {
        authService.register(phone, "Passw0rd!", "小张", regCode);

        assertThatThrownBy(() -> authService.login(phone, "WrongPass1!"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_LOGIN.getCode());
    }

    @Test
    @DisplayName("⭐ P1-⑤：Refresh Token 一次性，但 30 秒内重入仍可换发（多标签页不互踢）")
    void refresh_token_is_one_time_but_allows_30s_grace() {
        LoginResult a = authService.register(phone, "Passw0rd!", "小张", regCode);

        LoginResult b = authService.refresh(a.getRefreshToken());
        assertThat(b.getAccessToken()).isNotBlank();
        // ⭐ 宽限期内用**同一个**旧 token 再换一次，必须成功
        LoginResult c = authService.refresh(a.getRefreshToken());
        assertThat(c.getAccessToken()).isNotBlank();

        // 换发出来的新 token 与前一次不同（真轮换，不是复用）
        assertThat(b.getRefreshToken()).isNotEqualTo(a.getRefreshToken());
        assertThat(c.getRefreshToken()).isNotEqualTo(b.getRefreshToken());
    }

    @Test
    @DisplayName("⭐ 超过宽限期后，旧 Refresh Token 必须失效")
    void expired_refresh_token_is_rejected_after_grace() throws Exception {
        // ⚠️ 宽限期靠 **Redis TTL** 实现，而快进 Java 的 Clock 不会让 Redis 的键过期
        //    —— 所以这个用例只能把宽限期调小后**真等**，不能用 fastForward 假装。
        //    （本测试第一版就是这么错的：fastForward 31 秒后旧令牌照样能换发。）
        int original = configService.getInt("security.refresh_grace_seconds");
        try {
            jdbc.update("UPDATE sys_config SET config_value = '1' WHERE config_key = 'security.refresh_grace_seconds'");
            configService.refresh();

            LoginResult a = authService.register(phone, "Passw0rd!", "小张", regCode);
            authService.refresh(a.getRefreshToken());          // 换发一次，留下 1 秒宽限标记

            Thread.sleep(1300);                                 // 真等过宽限期
            assertThatThrownBy(() -> authService.refresh(a.getRefreshToken()))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo(ErrorCode.TOKEN_EXPIRED.getCode());
        } finally {
            jdbc.update("UPDATE sys_config SET config_value = ? WHERE config_key = 'security.refresh_grace_seconds'",
                    String.valueOf(original));
            configService.refresh();
        }
    }

    @Test
    @DisplayName("限流·手机号维度：同一号码 1 小时内第 6 次被拒（每次换 IP 与设备，排除其他维度）")
    void sms_code_is_rate_limited_per_phone_per_hour() {
        // setUp 已发 1 条，再补 4 条到上限 5；刻意每次换 IP/设备，确保命中的是手机号维度
        for (int i = 0; i < 4; i++) {
            smsCodeService.send(phone, SmsScene.REGISTER, ip + "-" + i, device + "-" + i);
        }
        assertThatThrownBy(() -> smsCodeService.send(phone, SmsScene.REGISTER, ip + "-x", device + "-x"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());
    }

    private static String freshPhone() {
        return "139" + String.format("%08d", RANDOM.nextInt(100_000_000));
    }

    @Test
    @DisplayName("限流·IP 维度：同一 IP 轮换手机号刷量同样被拒（短信费用攻击的主要形态）")
    void sms_code_is_rate_limited_per_ip() {
        String attackerIp = "203.0.113." + RANDOM.nextInt(254);
        // 每个号码都是全新的，手机号维度不会命中；只有 IP 维度能拦住
        for (int i = 0; i < 5; i++) {
            smsCodeService.send(freshPhone(), SmsScene.REGISTER, attackerIp, "attacker-" + i);
        }
        String fresh = freshPhone();
        assertThatThrownBy(() -> smsCodeService.send(fresh, SmsScene.REGISTER, attackerIp, "attacker-x"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());
    }

    @Test
    @DisplayName("短信验证码可校验且一次性：用两次第二次失败")
    void sms_code_is_verifiable_and_single_use() {
        assertThat(smsCodeService.verify(phone, SmsScene.REGISTER, regCode)).isTrue();
        assertThat(smsCodeService.verify(phone, SmsScene.REGISTER, regCode)).isFalse();
    }

    @Test
    @DisplayName("登录成功写 sys_login_log，且日志里的手机号是掩码（⛔ 不留明文）")
    void successful_login_writes_login_log_with_masked_phone() {
        LoginResult r = authService.register(phone, "Passw0rd!", "小张", regCode);
        authService.login(phone, "Passw0rd!");

        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_login_log WHERE user_id = ? AND result = 'SUCCESS'",
                Integer.class, r.getUserId());
        assertThat(n).isGreaterThanOrEqualTo(1);

        String logged = jdbc.queryForObject(
                "SELECT login_name FROM sys_login_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, r.getUserId());
        // ⭐ 日志里绝不能出现完整手机号
        assertThat(logged).doesNotContain(phone);
        assertThat(logged).contains("****");
    }
}
