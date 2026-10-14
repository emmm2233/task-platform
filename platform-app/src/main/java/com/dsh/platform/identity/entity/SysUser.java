package com.dsh.platform.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户（§5.4.2）。
 *
 * <p>⛔ {@code phone} 是<b>加密后的密文</b>；按键查找一律走 {@code phoneHash} 盲索引。
 * 本类不提供"按 phone 查询"的便利方法 —— 那样会诱导后来者写出明文查询。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外展示的用户号，形如 U2026101500001。 */
    private String userNo;

    /** 平台身份：USER / OPERATOR / ADMIN。 */
    private String identity;

    /** ⚠️ L3 密文（信封加密）。 */
    private String phone;

    /** ⭐ 盲索引：HMAC-SHA256(手机号, 盐)，唯一。 */
    private String phoneHash;

    /** BCrypt 哈希，⛔ 永不返回给客户端。 */
    private String passwordHash;

    private String nickname;

    private String avatarUrl;

    /** 实名状态：UNSUBMITTED / PENDING / PASSED / REJECTED。 */
    private String realNameStatus;

    /** 信誉分 0–100（G10）。 */
    private Integer creditScore;

    /** 信誉等级代码，由 credit_level 表解析。 */
    private String creditLevel;

    /** 账号状态：NORMAL / FROZEN / CANCELLED。 */
    private String status;

    private String freezeReason;

    private LocalDateTime frozenAt;

    private Long frozenBy;

    /** ⭐ 权限版本：角色变更时 +1，令牌校验时比对，实现"改权限立即生效"。 */
    private Integer permissionVersion;

    /** 来源端：web-user / web-admin / mp-weixin / app-android / app-ios。 */
    private String sourceClient;

    private LocalDateTime lastLoginAt;

    private String lastLoginIp;

    /** 乐观锁版本。 */
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
