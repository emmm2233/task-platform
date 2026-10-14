package com.dsh.platform.identity;

import com.dsh.platform.common.BizException;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.SmsCodeService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 短信验证码的<b>防护闸门</b>（不是"能不能收到码"，那是 AuthServiceTest 的事）。
 *
 * <p>修复前的三个真缺口（独立审查实测）：
 * <ol>
 *   <li>限流里的 {@code ip} 来自<b>请求体</b> ⇒ 每次随机填一个就绕开一维；</li>
 *   <li>javadoc 与契约都写「四维（含全局）」，而<b>全局那一维在代码里不存在</b>
 *       ⇒ 号码池攻击没有上限；</li>
 *   <li>{@code verify} 失败<b>不删码、不计数</b> ⇒ 6 位码（100 万种可能）
 *       在 5 分钟窗口内可被暴力猜解。</li>
 * </ol>
 *
 * <p>⛔ 第 1 条由 {@code AuthController} 取 {@code request.getRemoteAddr()} 解决，
 * 属于 HTTP 层；这里钉的是第 2、3 条（服务层）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmsCodeGuardTest {

    private static final Random RANDOM = new Random();

    @Autowired private SmsCodeService smsCodeService;
    @Autowired private StringRedisTemplate redis;

    @BeforeAll
    void clearCounters() {
        // 限流计数在 Redis 里跨测试类、跨运行存活（TTL 2 小时）——
        // 确定性清理，不靠随机号码降低概率。
        var keys = redis.keys("sms:limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        var attempts = redis.keys("sms:attempt:*");
        if (attempts != null && !attempts.isEmpty()) {
            redis.delete(attempts);
        }
    }

    private static String randomPhone() {
        return "139" + String.format("%08d", RANDOM.nextInt(100_000_000));
    }

    @Test
    @DisplayName("⭐ 校验通过后必须一次性消费（同码再验必须失败）")
    void verify_consumes_the_code() {
        String phone = randomPhone();
        String code = smsCodeService.send(phone, SmsScene.REGISTER, "10.0.0.1", "dev-1");

        assertThat(smsCodeService.verify(phone, SmsScene.REGISTER, code)).isTrue();
        assertThat(smsCodeService.verify(phone, SmsScene.REGISTER, code))
                .as("一次性：验过就必须删，否则同一个码可以被反复使用")
                .isFalse();
    }

    @Test
    @DisplayName("⭐⭐ 校验尝试次数超限即作废该码（防 6 位码被暴力猜解）")
    void too_many_wrong_attempts_invalidate_the_code() {
        String phone = randomPhone();
        String code = smsCodeService.send(phone, SmsScene.REGISTER, "10.0.0.2", "dev-2");

        // 配置里是 5 次；前 5 次只返回 false（不抛），第 6 次触发限流
        for (int i = 0; i < 5; i++) {
            assertThat(smsCodeService.verify(phone, SmsScene.REGISTER, "000000"))
                    .as("第 %d 次错误校验应为普通失败", i + 1)
                    .isFalse();
        }
        assertThatThrownBy(() -> smsCodeService.verify(phone, SmsScene.REGISTER, "000000"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("次数过多");

        // ⭐ 关键：超限后【连正确的码也不认】—— 必须重新获取（而重新获取要过四维+全局限流）
        assertThat(smsCodeService.verify(phone, SmsScene.REGISTER, code))
                .as("超限即作废，正确码也必须失效")
                .isFalse();
    }

    @Test
    @DisplayName("⭐ 重新发码会重置尝试计数（否则连错几次后拿到新码也解不开）")
    void resend_resets_attempt_counter() {
        String phone = randomPhone();
        smsCodeService.send(phone, SmsScene.REGISTER, "10.0.0.3", "dev-3");
        assertThat(smsCodeService.verify(phone, SmsScene.REGISTER, "111111")).isFalse();
        assertThat(smsCodeService.verify(phone, SmsScene.REGISTER, "222222")).isFalse();

        String fresh = smsCodeService.send(phone, SmsScene.REGISTER, "10.0.0.3", "dev-3");
        assertThat(smsCodeService.verify(phone, SmsScene.REGISTER, fresh))
                .as("新码必须可用（计数已重置）")
                .isTrue();
    }

    @Test
    @DisplayName("⭐ 按场景隔离：注册码不能拿去当别的场景的码")
    void code_is_scoped_by_scene() {
        String phone = randomPhone();
        String code = smsCodeService.send(phone, SmsScene.REGISTER, "10.0.0.4", "dev-4");

        assertThat(smsCodeService.verify(phone, SmsScene.RESET_PASSWORD, code))
                .as("跨场景必须失败，否则注册码可以拿去改密")
                .isFalse();
    }
}
