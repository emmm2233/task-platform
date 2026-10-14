package com.dsh.platform.identity.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 登录 / 注册 / 换发令牌的统一返回体。 */
@Data
@Builder
public class LoginResult {

    private Long userId;

    private String userNo;

    private String nickname;

    private String identity;

    /** 访问令牌（JWT，默认 15 分钟）。 */
    private String accessToken;

    /** 刷新令牌（不透明随机串，默认 7 天，一次性 + 30 秒宽限期）。 */
    private String refreshToken;

    /** 访问令牌剩余秒数。 */
    private Long expiresIn;

    /** 信誉分（G10：初始 60）。 */
    private Integer creditScore;

    private String creditLevel;

    private String realNameStatus;

    /** 已授予角色（供前端裁剪菜单；⛔ 前端裁剪只是体验，不是安全措施）。 */
    private List<String> roles;

    private Integer permissionVersion;
}