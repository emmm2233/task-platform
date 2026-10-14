package com.dsh.platform.identity.security;

import com.dsh.platform.identity.entity.SysUser;
import com.dsh.platform.identity.mapper.SysUserMapper;
import com.dsh.platform.identity.service.TokenService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JWT 认证过滤器 —— 三层鉴权的第一、二层在这里落地。
 *
 * <p>做四件事：
 * <ol>
 *   <li>解析访问令牌，拿到 userId；</li>
 *   <li>⭐ 比对令牌里的 {@code pv}（权限版本）与库里的 {@code permission_version} ——
 *       <b>不一致即拒绝</b>。这是"改了角色立即生效"的实现方式：
 *       不需要遍历该用户的所有令牌去作废，只要版本对不上就自然失效。</li>
 *   <li>装载角色与权限点，塞进 {@code Authentication} 的 authorities；</li>
 *   <li>把 {@link DataScope.Principal} 放进请求属性，供业务层做数据范围判定。</li>
 * </ol>
 *
 * <p>⛔ 认证失败<b>不抛异常</b>，只清空 SecurityContext 后放行 ——
 * 让后面的 {@code ExceptionTranslationFilter} 按路径规则决定是 401 还是 403。
 * 这里直接写响应会让公开接口（健康检查、登录）也拿不到请求。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 请求属性名：业务层从 HttpServletRequest 取当前主体。 */
    public static final String ATTR_PRINCIPAL = "platform.principal";

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final SysUserMapper userMapper;
    private final PermissionCache permissionCache;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            authenticate(request, header.substring(PREFIX.length()).trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        Claims claims = tokenService.parseAccessToken(token);
        if (claims == null) {
            return;
        }
        Long userId = Long.valueOf(claims.getSubject());
        SysUser user = userMapper.selectById(userId);
        if (user == null || !"NORMAL".equals(user.getStatus())) {
            return;
        }

        // ⭐ 权限版本比对：角色变更后旧令牌立即失效（不必等它过期）
        Integer tokenPv = claims.get("pv", Integer.class);
        if (!Objects.equals(tokenPv, user.getPermissionVersion())) {
            return;
        }

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        // 角色代码（ROLE_ 前缀，可直接用于 hasRole / hasAuthority）
        for (String role : permissionCache.rolesOf(userId)) {
            authorities.add(new SimpleGrantedAuthority(role));
        }
        // 权限点（domain:resource:action，用于 @PreAuthorize("hasAuthority(...)")）
        for (String perm : permissionCache.permissionsOf(userId)) {
            authorities.add(new SimpleGrantedAuthority(perm));
        }
        // 平台身份的合成权限：路径域规则用它区分 user / op / admin
        authorities.add(new SimpleGrantedAuthority("IDENTITY_" + user.getIdentity()));

        DataScope.Principal principal = permissionCache.principalOf(user);
        request.setAttribute(ATTR_PRINCIPAL, principal);

        var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
