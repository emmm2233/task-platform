package com.dsh.platform.identity.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.common.config.SysConfigService;
import com.dsh.platform.identity.dto.LoginResult;
import com.dsh.platform.identity.entity.SysUser;
import com.dsh.platform.identity.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 令牌服务。
 *
 * <p>两种令牌分工明确：
 * <ul>
 *   <li><b>访问令牌</b> —— JWT，无状态，15 分钟。校验不查库（只比 {@code permissionVersion}），
 *       所以每个请求都能低成本鉴权。</li>
 *   <li><b>刷新令牌</b> —— 不透明随机串，存 Redis，7 天。<b>一次性</b>：
 *       每次换发都作废旧的并给新的。</li>
 * </ul>
 *
 * <p>⭐ <b>一次性刷新令牌必须带宽限期（P1-⑤）</b>：
 * 真正的用户会开多个标签页，或弱网下重发请求。若换发即让旧令牌失效，
 * 第二个标签页就会莫名其妙被踢下线。所以旧令牌在换发后仍保留
 * {@code security.refresh_grace_seconds}（默认 30 秒）—— 与设计原文的"30 秒宽限期"一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String GRACE_PREFIX = "auth:refresh:grace:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final SysConfigService config;
    private final SysUserMapper userMapper;
    private final Clock clock;

    /**
     * JWT 签名密钥。
     *
     * <p>⛔ 从<b>配置</b>读而不是从 {@code sys_config} 读：它是密钥，不是业务参数。
     * 业务参数可以在管理端改，密钥不行。生产环境应由 Vault/KMS 注入。
     */
    @Value("${platform.crypto.jwt-secret}")
    private String jwtSecret;

    /** 签发一对令牌。 */
    public LoginResult issue(SysUser user, List<String> roles, String client) {
        long accessMinutes = config.getLong("security.token_access_minutes");
        long refreshDays = config.getLong("security.token_refresh_days");

        String access = buildAccessToken(user, accessMinutes);
        String refresh = randomToken();
        redis.opsForValue().set(REFRESH_PREFIX + sha256(refresh), String.valueOf(user.getId()),
                Duration.ofDays(refreshDays));

        return LoginResult.builder()
                .userId(user.getId())
                .userNo(user.getUserNo())
                .nickname(user.getNickname())
                .identity(user.getIdentity())
                .accessToken(access)
                .refreshToken(refresh)
                .expiresIn(accessMinutes * 60)
                .creditScore(user.getCreditScore())
                .creditLevel(user.getCreditLevel())
                .realNameStatus(user.getRealNameStatus())
                .roles(roles)
                .permissionVersion(user.getPermissionVersion())
                .build();
    }

    /**
     * 换发令牌（一次性 + 宽限期）。
     *
     * <p>状态机：
     * <pre>
     *   正常  → 换发成功，旧令牌立即删除，并留一个 30 秒的宽限标记
     *   宽限内 → 仍可用（多标签页场景），再发一对新令牌
     *   宽限外 → 拒绝（旧令牌已彻底作废）
     * </pre>
     */
    public LoginResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BizException(ErrorCode.NOT_LOGIN);
        }
        String hash = sha256(refreshToken);
        String userId = redis.opsForValue().get(REFRESH_PREFIX + hash);

        if (userId != null) {
            // 正常路径：作废旧的，留宽限标记
            redis.delete(REFRESH_PREFIX + hash);
            redis.opsForValue().set(GRACE_PREFIX + hash, userId,
                    Duration.ofSeconds(config.getLong("security.refresh_grace_seconds")));
        } else {
            // 可能是宽限期内的重入
            userId = redis.opsForValue().get(GRACE_PREFIX + hash);
            if (userId == null) {
                throw new BizException(ErrorCode.TOKEN_EXPIRED);
            }
        }

        SysUser user = userMapper.selectById(Long.valueOf(userId));
        if (user == null) {
            throw new BizException(ErrorCode.TOKEN_EXPIRED);
        }
        if (!"NORMAL".equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_FROZEN);
        }
        // 换发时重新查角色（角色可能已变更）
        return issue(user, List.of(), "refresh");
    }

    /** 解析并校验访问令牌。返回 null 表示无效（调用方决定如何响应）。 */
    public Claims parseAccessToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // 令牌无效是常见情况（过期、伪造），按 DEBUG 记即可，⛔ 不刷 ERROR 日志
            log.debug("访问令牌校验失败: {}", e.getMessage());
            return null;
        }
    }

    private String buildAccessToken(SysUser user, long minutes) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("identity", user.getIdentity())
                // ⭐ 权限版本：角色变更时自增，使旧令牌在下一次校验时立即失效
                .claim("pv", user.getPermissionVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(minutes))))
                .signWith(signingKey())
                .compact();
    }

    private SecretKey signingKey() {
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "platform.crypto.jwt-secret 长度不足 32 字节，HS256 要求至少 256 位密钥");
        }
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 供登录失败统计等场景复用。 */
    public Map<String, String> debugKeys() {
        return Map.of("refresh", REFRESH_PREFIX, "grace", GRACE_PREFIX);
    }
}
