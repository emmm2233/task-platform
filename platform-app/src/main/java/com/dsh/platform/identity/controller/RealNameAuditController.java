package com.dsh.platform.identity.controller;

import com.dsh.platform.common.ApiResponse;
import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.identity.security.DataScope;
import com.dsh.platform.identity.security.JwtAuthenticationFilter;
import com.dsh.platform.identity.service.RealNameService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运营侧实名审核接口。
 *
 * <p>⭐ {@code realname:audit} 是<b>第二层（权限点）</b>的落点：
 * 路径域只保证"是运营人员"，本注解进一步保证"是<b>实名</b>审核员"。
 * 内容审核员同属运营域但<b>没有</b>这个权限点 —— 这正是 Q15 互斥岗位对
 * （内容审核员 ⊗ 实名审核员）要防的事：不让一个人同时看全用户内容与证件。
 */
@RestController
@RequestMapping("/api/v1/op/real-name-audits")
@RequiredArgsConstructor
public class RealNameAuditController {

    private final RealNameService realNameService;

    @GetMapping("/{realNameId}")
    @PreAuthorize("hasAuthority('realname:audit')")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("realNameId") Long realNameId,
                                                   HttpServletRequest request) {
        DataScope.Principal p =
                (DataScope.Principal) request.getAttribute(JwtAuthenticationFilter.ATTR_PRINCIPAL);
        if (p == null) {
            throw new BizException(ErrorCode.NOT_LOGIN);
        }
        return ApiResponse.ok(realNameService.viewForAudit(realNameId, p, clientIp(request)));
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return (xff == null || xff.isBlank()) ? request.getRemoteAddr() : xff.split(",")[0].trim();
    }
}