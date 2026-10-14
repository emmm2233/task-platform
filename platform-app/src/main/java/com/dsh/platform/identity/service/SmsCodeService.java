package com.dsh.platform.identity.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.common.config.SysConfigService;
import com.dsh.platform.identity.dto.SmsScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 短信验证码：发送、限流、校验。
 *
 * <p>⭐ 限流必须是<b>四维</b>的，只按手机号限流等于没限：
 * <table border="1">
 *   <caption>四个维度各挡一类攻击</caption>
 *   <tr><th>维度</th><th>挡什么</th></tr>
 *   <tr><td>手机号</td><td>对同一号码的轰炸（用户投诉的主要来源）</td></tr>
 *   <tr><td>IP</td><td>一台机器轮换号码批量发送（短信费用攻击）</td></tr>
 *   <tr><td>设备</td><td>换 IP 但设备不变（代理池绕过）</td></tr>
 *   <tr><td>全局</td><td>分布式大规模刷量（兜底闸门）</td></tr>
 * </table>
 *
 * <p>计数器按<b>自然小时</b>分桶（键里带 {@code yyyyMMddHH}），
 * 这样无需定时清理任务，桶自己过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsCodeService {

    private static final String CODE_KEY = "sms:code:";
    private static final String LIMIT_KEY = "sms:limit:";
    /** 校验尝试计数（与验证码同寿命）。 */
    private static final String ATTEMPT_KEY = "sms:attempt:";
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final SysConfigService config;
    private final Clock clock;

    /**
     * 发送验证码，返回验证码本身。
     *
     * <p>⚠️ 返回验证码是为了让测试与本地开发可用。生产环境应改为
     * 「调用短信网关 + 返回 void」，⛔ 绝不能把验证码放进接口响应
     * （{@code AuthController} 已丢弃返回值 —— 有测试钉住这一点）。
     *
     * <p>⛔⛔ {@code ip} 必须由<b>服务端</b>取（{@code request.getRemoteAddr()}），
     * <b>不能</b>取请求体里的字段：客户端只要每次随机填一个值，这一维就形同不存在。
     * @param deviceId 设备指纹（客户端提供，天生可伪造 ⇒ 只算"软"维度，
     *                 真正的兜底是下面的 global 闸门）
     */
    public String send(String phone, SmsScene scene, String ip, String deviceId) {
        LocalDateTime now = LocalDateTime.now(clock);
        int hourLimit = config.getInt("security.sms_hour_limit");
        int dayLimit = config.getInt("security.sms_day_limit");

        checkLimit(phone, "phone", hourLimit, HOUR.format(now), Duration.ofHours(2));
        checkLimit(phone, "phone", dayLimit, DAY.format(now), Duration.ofDays(2));
        checkLimit(ip, "ip", hourLimit, HOUR.format(now), Duration.ofHours(2));
        checkLimit(deviceId, "device", hourLimit, HOUR.format(now), Duration.ofHours(2));
        // ⭐ 全局闸门：只按手机号/IP/设备限流挡不住"号码池 + 每次伪造 ip/deviceId"，
        //    分布式刷量必须有一个人为上限兜底（否则就是无上限的短信账单）。
        checkLimit(GLOBAL, "global", config.getInt("security.sms_global_hour_limit"),
                HOUR.format(now), Duration.ofHours(2));

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(CODE_KEY + scene + ":" + phone, code, Duration.ofMinutes(5));
        // 重新发码 = 重置校验尝试次数（否则用户连错几次后再要一个新码也解不开）
        redis.delete(ATTEMPT_KEY + scene + ":" + phone);

        // ⛔ 日志里只留掩码
        log.info("发送短信验证码 phone={} scene={}", mask(phone), scene);
        return code;
    }

    /**
     * 校验并消费验证码（一次性：校验通过即删除）。
     *
     * <p>⭐ <b>必须计尝试次数</b>：6 位码只有 100 万种可能，
     * 而码的寿命是 5 分钟 —— 不计次、不删码的话，脚本可以在窗口内把空间刷一遍。
     * 超限即<b>作废该码</b>（逼用户重新获取，而重新获取要过四维 + 全局限流）。
     */
    public boolean verify(String phone, SmsScene scene, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String key = CODE_KEY + scene + ":" + phone;
        String expected = redis.opsForValue().get(key);
        if (expected == null) {
            // 没有码 / 已被消费：不消耗尝试次数（否则正常用户点两次就自我锁死）
            return false;
        }

        String attemptKey = ATTEMPT_KEY + scene + ":" + phone;
        Long attempts = redis.opsForValue().increment(attemptKey);
        if (attempts != null && attempts == 1L) {
            redis.expire(attemptKey, Duration.ofMinutes(5));
        }
        int maxAttempts = config.getInt("security.sms_verify_max_attempts");
        if (attempts != null && attempts > maxAttempts) {
            redis.delete(key);
            redis.delete(attemptKey);
            log.warn("验证码校验次数超限，已作废 phone={} scene={} attempts={}", mask(phone), scene, attempts);
            throw new BizException(ErrorCode.RATE_LIMITED, "验证码错误次数过多，请重新获取");
        }

        // ⛔ 常量时间比较：逐字符 equals 会按"第几位不同"泄露时间差
        if (!constantTimeEquals(expected, code.trim())) {
            return false;
        }
        redis.delete(key);
        redis.delete(attemptKey);
        return true;
    }

    /** 全局维度的伪主体（仅用于限流键，不对应任何真实手机号/IP）。 */
    private static final String GLOBAL = "all";

    /** 常量时间比较，避免按字符位置泄露信息。 */
    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计数并判断是否超限。
     *
     * <p>先自增再比较 —— ⛔ 不要"先读再写"，那在并发下会放过超额请求。
     */
    private void checkLimit(String subject, String dim, int limit, String bucket, Duration ttl) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        String key = LIMIT_KEY + dim + ":" + subject + ":" + bucket;
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1L) {
            redis.expire(key, ttl);
        }
        if (n != null && n > limit) {
            log.warn("短信限流命中 dim={} subject={} 第 {} 次（上限 {}）", dim, mask(subject), n, limit);
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
    }

    private static String mask(String s) {
        if (s == null || s.length() < 7) {
            return "***";
        }
        return s.substring(0, 3) + "****" + s.substring(s.length() - 4);
    }
}
