package com.dsh.platform.identity.controller;

import com.dsh.platform.common.ApiResponse;
import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.identity.entity.SysUser;
import com.dsh.platform.identity.mapper.SysUserMapper;
import com.dsh.platform.identity.security.DataScope;
import com.dsh.platform.identity.security.JwtAuthenticationFilter;
import com.dsh.platform.identity.security.PermissionCache;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 账号自助接口。
 *
 * <p>{@code GET /account/profile} 是四端启动后必调的接口：
 * 拿到信誉分、等级、实名状态、角色，据此决定首页展示什么、哪些入口置灰。
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class AccountController {

    private final SysUserMapper userMapper;
    private final PermissionCache permissionCache;

    /**
     * 当前登录用户的资料。
     *
     * <p>⛔ 手机号只返回掩码，绝不返回密文或明文 —— 客户端不需要它。
     */
    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile(HttpServletRequest request) {
        DataScope.Principal principal =
                (DataScope.Principal) request.getAttribute(JwtAuthenticationFilter.ATTR_PRINCIPAL);
        if (principal == null) {
            throw new BizException(ErrorCode.NOT_LOGIN);
        }

        SysUser u = userMapper.selectById(principal.userId());
        if (u == null) {
            throw new BizException(ErrorCode.NOT_LOGIN);
        }

        List<String> permissions = permissionCache.permissionsOf(u.getId());

        // ⚠️ Map.of() 最多支持 10 对键值；这里超过 10 对，必须用 Map.ofEntries 或 LinkedHashMap
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("userId", u.getId());
        body.put("userNo", u.getUserNo());
        body.put("nickname", u.getNickname());
        body.put("identity", u.getIdentity());
        body.put("realNameStatus", u.getRealNameStatus());
        body.put("creditScore", u.getCreditScore());
        body.put("creditLevel", u.getCreditLevel());
        body.put("dataScope", principal.scope());
        body.put("roles", permissionCache.rolesOf(u.getId()));
        // 前端据此裁剪按钮；⛔ 这只是体验优化，真正的拦截在后端每一层
        body.put("permissions", permissions);
        body.put("permissionVersion", u.getPermissionVersion());
        body.put("sourceClient", u.getSourceClient());
        return ApiResponse.ok(body);
    }
}
