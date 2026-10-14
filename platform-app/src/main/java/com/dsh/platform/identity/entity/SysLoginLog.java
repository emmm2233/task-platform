package com.dsh.platform.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 登录日志（合规要求：登录行为可追溯）。 */
@Data
@TableName("sys_login_log")
public class SysLoginLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 登录名（⛔ 落库前须脱敏，不存明文手机号）。 */
    private String loginName;

    /** SUCCESS / FAIL_PASSWORD / FAIL_FROZEN / FAIL_NOT_EXIST / FAIL_LOCKED。 */
    private String result;

    private String failReason;

    private String client;

    private String ip;

    private String ipRegion;

    private String deviceId;

    private String userAgent;

    private String riskTag;

    private LocalDateTime createdAt;
}