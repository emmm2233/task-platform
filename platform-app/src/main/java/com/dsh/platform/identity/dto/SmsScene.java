package com.dsh.platform.identity.dto;

/** 短信场景。不同场景的验证码互不通用（防止拿注册码去重置密码）。 */
public enum SmsScene {
    REGISTER,
    LOGIN,
    RESET_PASSWORD,
    CHANGE_PHONE
}