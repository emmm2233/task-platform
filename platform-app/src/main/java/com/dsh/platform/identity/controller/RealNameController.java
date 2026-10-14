package com.dsh.platform.identity.controller;

import com.dsh.platform.common.ApiResponse;
import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.identity.security.DataScope;
import com.dsh.platform.identity.security.JwtAuthenticationFilter;
import com.dsh.platform.identity.service.RealNameService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 用户侧实名认证接口。 */
@RestController
@RequestMapping("/api/v1/account/real-name")
@RequiredArgsConstructor
public class RealNameController {

    private final RealNameService realNameService;

    @PostMapping
    public ApiResponse<Map<String, Object>> submit(@Valid @RequestBody SubmitRequest req,
                                                   HttpServletRequest request) {
        Long userId = currentUserId(request);
        Long id = realNameService.submit(userId, req.getRealType(), req.getRealName(),
                req.getIdCardNo(), req.getIdCardFront(), req.getIdCardBack());
        return ApiResponse.ok(Map.of("realNameId", id, "status", "PENDING"));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> mine(HttpServletRequest request) {
        return ApiResponse.ok(realNameService.myRealName(currentUserId(request)));
    }

    private Long currentUserId(HttpServletRequest request) {
        DataScope.Principal p =
                (DataScope.Principal) request.getAttribute(JwtAuthenticationFilter.ATTR_PRINCIPAL);
        if (p == null) {
            throw new BizException(ErrorCode.NOT_LOGIN);
        }
        return p.userId();
    }

    @Data
    public static class SubmitRequest {
        @NotBlank
        @Pattern(regexp = "PERSONAL|ENTERPRISE", message = "实名类型只能是 PERSONAL 或 ENTERPRISE")
        private String realType;

        @NotBlank
        private String realName;

        /** 身份证号：15 位（老版）或 18 位。 */
        @NotBlank
        @Pattern(regexp = "^\\d{15}$|^\\d{17}[\\dXx]$", message = "身份证号格式不正确")
        private String idCardNo;

        @NotBlank
        private String idCardFront;

        @NotBlank
        private String idCardBack;
    }
}