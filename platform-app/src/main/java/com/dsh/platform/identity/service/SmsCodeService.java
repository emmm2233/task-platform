package com.dsh.platform.identity.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.common.config.SysConfigService;
import com.dsh.platform.identity.dto.SmsScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
     * 「调用短信网关 + 返回 void」，⛔ 绝不能把验证码放进接口响应。
     */
    public String send(String phone, SmsScene scene, String ip, String deviceId) {
        LocalDateTime now = LocalDateTime.now(clock);
        int hourLimit = config.getInt("security.sms_hour_limit");
        int dayLimit = config.getInt("security.sms_day_limit");

        checkLimit(phone, "phone", hourLimit, HOUR.format(now), Duration.ofHours(2));
        checkLimit(phone, "phone", dayLimit, DAY.format(now), Duration.ofDays(2));
        checkLimit(ip, "ip", hourLimit, HOUR.format(now), Duration.ofHours(2));
        checkLimit(deviceId, "device", hourLimit, HOUR.format(now), Duration.ofHours(2));

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(CODE_KEY + scene + ":" + phone, code, Duration.ofMinutes(5));

        // ⛔ 日志里只留掩码
        log.info("发送短信验证码 phone={} scene={}", mask(phone), scene);
        return code;
    }

    /** 校验并消费验证码（一次性：校验通过即删除）。 */
    public boolean verify(String phone, SmsScene scene, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String key = CODE_KEY + scene + ":" + phone;
        String expected = redis.opsForValue().get(key);
        if (expected == null || !expected.equals(code.trim())) {
            return false;
        }
        redis.delete(key);
        return true;
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
