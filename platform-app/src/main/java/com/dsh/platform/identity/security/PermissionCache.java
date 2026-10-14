package com.dsh.platform.identity.security;

import com.dsh.platform.identity.entity.SysUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 权限装载。
 *
 * <p>⛔ 为什么每次请求都查一遍而不缓存：
 * 授权数据是<b>安全关键</b>的。缓存一旦失效不及时，被撤销权限的人还能继续操作
 * —— 这在等保测评里是明确的扣分项。
 * Phase 1 的规模下（单用户 ≤ 11 个角色 ≤ 38 个权限点）两条索引查询的开销可以接受。
 * Phase 2 若成为瓶颈，再引入带 {@code permissionVersion} 作废键的 Redis 缓存。
 */
@Service
@RequiredArgsConstructor
public class PermissionCache {

    private final JdbcTemplate jdbc;

    /** 已授予的角色代码。 */
    public List<String> rolesOf(Long userId) {
        return jdbc.queryForList("""
                SELECT r.role_code
                  FROM sys_user_role ur
                  JOIN sys_role r ON r.id = ur.role_id
                 WHERE ur.user_id = ? AND r.enabled = 1
                """, String.class, userId);
    }

    /** 角色带来的权限点代码（去重）。 */
    public List<String> permissionsOf(Long userId) {
        return jdbc.queryForList("""
                SELECT DISTINCT p.perm_code
                  FROM sys_user_role ur
                  JOIN sys_role r ON r.id = ur.role_id AND r.enabled = 1
                  JOIN sys_role_permission rp ON rp.role_id = r.id
                  JOIN sys_permission p ON p.id = rp.permission_id
                 WHERE ur.user_id = ?
                """, String.class, userId);
    }

    /** 数据范围（取最宽）。 */
    public List<String> scopesOf(Long userId) {
        return jdbc.queryForList("""
                SELECT DISTINCT r.data_scope
                  FROM sys_user_role ur
                  JOIN sys_role r ON r.id = ur.role_id AND r.enabled = 1
                 WHERE ur.user_id = ?
                """, String.class, userId);
    }

    /** 组装请求主体。 */
    public DataScope.Principal principalOf(SysUser user) {
        return new DataScope.Principal(
                user.getId(),
                user.getIdentity(),
                DataScope.mergeScope(scopesOf(user.getId())),
                user.getRealNameStatus());
    }
}
