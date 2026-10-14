package com.dsh.platform.identity.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.common.config.CryptoConfig;
import com.dsh.platform.common.config.SysConfigService;
import com.dsh.platform.common.crypto.EnvelopeCipher;
import com.dsh.platform.identity.dto.LoginResult;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.entity.SysLoginLog;
import com.dsh.platform.identity.entity.SysUser;
import com.dsh.platform.identity.mapper.SysLoginLogMapper;
import com.dsh.platform.identity.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 认证服务：注册 / 登录 / 换发令牌。
 *
 * <p>三条安全约定：
 * <ol>
 *   <li>⛔ 「手机号不存在」与「密码错误」返回<b>同一个错误码</b>，
 *       否则接口就成了「这个手机号注册过没有」的查询器。</li>
 *   <li>⛔ 明文手机号不落库、不进日志。库里存密文（{@code phone}）+
 *       盲索引（{@code phoneHash}），日志里只留掩码。</li>
 *   <li>登录失败次数达到阈值即锁定（{@code security.login_fail_lock_count}）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String FAIL_PREFIX = "auth:fail:";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SysUserMapper userMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final TokenService tokenService;
    private final SmsCodeService smsCodeService;
    private final SysConfigService config;
    private final EnvelopeCipher cipher;
    private final CryptoConfig.PhoneHasher phoneHasher;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final Clock clock;

    /**
     * 注册。
     *
     * <p>信誉分与等级取自系统参数（G10：初始 60 / NORMAL），
     * ⛔ 不在代码里写死 —— 运营调整后新用户应立刻按新值初始化。
     */
    @Transactional
    public LoginResult register(String phone, String rawPassword, String nickname, String smsCode) {
        String hash = phoneHasher.hash(phone);
        if (userMapper.selectByPhoneHash(hash) != null) {
            // ⚠️ 注册场景可以明确告知"已注册" —— 用户需要知道该去登录还是找回密码。
            //    登录场景则必须模糊化（见 login 的注释）。
            throw new BizException(ErrorCode.PARAM_INVALID, "该手机号已注册，请直接登录或找回密码");
        }
        if (!smsCodeService.verify(phone, SmsScene.REGISTER, smsCode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "验证码错误或已失效");
        }

        SysUser u = new SysUser();
        u.setUserNo(nextUserNo());
        u.setIdentity("USER");
        u.setPhone(cipher.encrypt(phone));
        u.setPhoneHash(hash);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setNickname(nickname);
        u.setRealNameStatus("UNSUBMITTED");
        u.setCreditScore(config.getInt("credit.initial_score"));
        u.setCreditLevel("NORMAL");
        u.setStatus("NORMAL");
        u.setPermissionVersion(1);
        u.setSourceClient("web-user");
        u.setVersion(0);
        userMapper.insert(u);

        return tokenService.issue(u, List.of("ROLE_USER"), "web-user");
    }

    /**
     * 登录。
     *
     * <p>⛔ 「用户不存在」「密码错误」「账号被冻结」对外都用 {@link ErrorCode#NOT_LOGIN} 或
     * 语义等价的模糊文案。原因：登录接口是公开的，任何能区分这些情况的信息
     * 都能被用来枚举「哪些手机号在本平台注册过」。
     * 真实原因写进 {@code sys_login_log} 供风控分析。
     */
    @Transactional
    public LoginResult login(String phone, String rawPassword) {
        String hash = phoneHasher.hash(phone);
        String failKey = FAIL_PREFIX + hash;

        long failCount = parseLong(redis.opsForValue().get(failKey));
        int lockCount = config.getInt("security.login_fail_lock_count");
        if (failCount >= lockCount) {
            writeLog(null, mask(phone), "FAIL_LOCKED", "失败次数超限", "web-user");
            throw new BizException(ErrorCode.ACCOUNT_LOCKED);
        }

        SysUser u = userMapper.selectByPhoneHash(hash);
        if (u == null || !passwordEncoder.matches(rawPassword, u.getPasswordHash())) {
            long n = redis.opsForValue().increment(failKey);
            redis.expire(failKey, Duration.ofMinutes(config.getLong("security.login_fail_lock_minutes")));
            writeLog(u == null ? null : u.getId(), mask(phone),
                    u == null ? "FAIL_NOT_EXIST" : "FAIL_PASSWORD",
                    u == null ? "账号不存在" : "密码错误", "web-user");
            log.warn("登录失败 phone={} 第 {} 次", mask(phone), n);
            throw new BizException(ErrorCode.NOT_LOGIN, "手机号或密码不正确");
        }

        if (!"NORMAL".equals(u.getStatus())) {
            writeLog(u.getId(), mask(phone), "FAIL_FROZEN", u.getFreezeReason(), "web-user");
            throw new BizException(ErrorCode.ACCOUNT_FROZEN);
        }

        redis.delete(failKey);
        u.setLastLoginAt(LocalDateTime.now(clock));
        userMapper.updateById(u);
        writeLog(u.getId(), mask(phone), "SUCCESS", null, "web-user");

        return tokenService.issue(u, List.of("ROLE_USER"), "web-user");
    }

    /** 换发令牌。 */
    public LoginResult refresh(String refreshToken) {
        return tokenService.refresh(refreshToken);
    }

    // ------------------------------------------------------------------

    /** 用户号：U + 年月日 + 5 位随机。真实实现应走号段服务，Phase 1 先用随机 + 唯一索引兜底。 */
    private String nextUserNo() {
        return "U" + LocalDateTime.now(clock).toInstant(java.time.ZoneOffset.ofHours(8))
                .atZone(ZONE).toLocalDate().toString().replace("-", "")
                + String.format("%05d", ThreadLocalRandom.current().nextInt(100000));
    }

    /** 日志与审计里只留掩码：138****8000。⛔ 绝不写明文。 */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void writeLog(Long userId, String loginName, String result, String reason, String client) {
        SysLoginLog l = new SysLoginLog();
        l.setUserId(userId);
        l.setLoginName(loginName);
        l.setResult(result);
        l.setFailReason(reason);
        l.setClient(client);
        l.setCreatedAt(LocalDateTime.now(clock));
        loginLogMapper.insert(l);
    }
}
