package com.dsh.platform.identity.controller;

import com.dsh.platform.common.ApiResponse;
import com.dsh.platform.identity.dto.LoginResult;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.SmsCodeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>⛔ 这些接口是<b>公开</b>的（见 {@code SecurityBaselineConfig} 的说明），
 * 因此每个都要自带防护：验证码四维限流、登录失败锁定、密码强度校验。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SmsCodeService smsCodeService;

    @PostMapping("/sms-code")
    public ApiResponse<Void> sendSmsCode(@Valid @RequestBody SmsCodeRequest req) {
        // ⚠️ 生产环境这里只发短信、不返回验证码；返回值在 controller 层被丢弃
        smsCodeService.send(req.getPhone(), SmsScene.valueOf(req.getScene()),
                req.getIp(), req.getDeviceId());
        return ApiResponse.ok();
    }

    @PostMapping("/register")
    public ApiResponse<LoginResult> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.ok(authService.register(
                req.getPhone(), req.getPassword(), req.getNickname(), req.getSmsCode()));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req.getPhone(), req.getPassword()));
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResult> refresh(@Valid @RequestBody RefreshRequest req) {
        return ApiResponse.ok(authService.refresh(req.getRefreshToken()));
    }

    // ------------------------------------------------------------------

    @Data
    public static class SmsCodeRequest {
        @NotBlank
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phone;

        @NotBlank
        private String scene;

        private String ip;

        private String deviceId;
    }

    @Data
    public static class RegisterRequest {
        @NotBlank
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phone;

        /** 强密码策略：8–32 位，必须含字母与数字。 */
        @NotBlank
        @Size(min = 8, max = 32, message = "密码长度需 8–32 位")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码需同时包含字母与数字")
        private String password;

        @NotBlank
        @Size(max = 20)
        private String nickname;

        @NotBlank
        private String smsCode;
    }

    @Data
    public static class LoginRequest {
        @NotBlank
        private String phone;

        @NotBlank
        private String password;
    }

    @Data
    public static class RefreshRequest {
        @NotBlank
        private String refreshToken;
    }
}
