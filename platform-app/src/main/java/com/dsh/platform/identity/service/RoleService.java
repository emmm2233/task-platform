package com.dsh.platform.identity.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色分配（含岗位互斥校验）。
 *
 * <p>⭐ 互斥校验必须<b>双向</b>：
 * {@code sys_role_conflict} 每对只存一行（如 {@code (ROLE_RISK_ADMIN, ROLE_ARBITER)}）。
 * 如果只比对 {@code (a, b)} 的书写顺序，把两个角色的先后调换就能绕过 ——
 * 这是这类校验最经典的写错方式，所以单独用一个用例把它钉住。
 *
 * <p>为什么互斥要做成硬约束而不是提示：
 * 这些组合都是<b>职责分离</b>要求（裁决权与资金执行权分离、审过的任务不能自己裁）。
 * 允许"提示但放行"等于没有这个约束 —— 等保与内控都会判不合格。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final JdbcTemplate jdbc;

    /**
     * 覆盖式分配角色。
     *
     * @param userId     目标用户
     * @param roleCodes  角色代码集合（会替换掉原有角色）
     * @param operatorId 操作人（写入 sys_user_role.granted_by，便于追责）
     * @param reason     分配原因（审计要求）
     */
    @Transactional
    public void assignRoles(Long userId, List<String> roleCodes, Long operatorId, String reason) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "至少需要分配一个岗位");
        }
        Set<String> codes = new HashSet<>(roleCodes);

        // ① 角色必须真实存在 —— 否则会写出一堆指向不存在角色的关联，造成"看起来有权限"
        List<Long> roleIds = jdbc.queryForList(
                "SELECT id FROM sys_role WHERE role_code IN (" + placeholders(codes.size()) + ") AND enabled = 1",
                Long.class, codes.toArray());
        if (roleIds.size() != codes.size()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "存在无效或已停用的岗位代码");
        }

        // ② ⭐ 互斥校验（双向）
        List<String> conflicts = findConflicts(codes);
        if (!conflicts.isEmpty()) {
            throw new BizException(ErrorCode.ROLE_CONFLICT,
                    "岗位互斥，不可同时分配：" + String.join("；", conflicts));
        }

        List<Long> targetRoleIds = jdbc.queryForList(
                "SELECT id FROM sys_role WHERE role_code IN (" + placeholders(codes.size()) + ")",
                Long.class, codes.toArray());

        // ③ 覆盖式写入
        jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        for (Long roleId : targetRoleIds) {
            jdbc.update("INSERT INTO sys_user_role (user_id, role_id, granted_by, reason) VALUES (?,?,?,?)",
                    userId, roleId, operatorId, reason);
        }

        // ④ ⭐ 权限版本 +1：使该用户<b>已签发</b>的访问令牌在下一次请求时立即失效。
        //    不需要遍历作废令牌 —— JwtAuthenticationFilter 会比对 pv 并拒绝不一致的令牌。
        jdbc.update("UPDATE sys_user SET permission_version = permission_version + 1 WHERE id = ?", userId);

        log.info("分配岗位 userId={} roles={} by={} 原因={}", userId, codes, operatorId, reason);
    }

    /** 双向查找互斥组合，返回可读的冲突说明。 */
    private List<String> findConflicts(Set<String> codes) {
        List<String> out = new java.util.ArrayList<>();
        jdbc.query("SELECT role_code_a, role_code_b, reason FROM sys_role_conflict", rs -> {
            String a = rs.getString("role_code_a");
            String b = rs.getString("role_code_b");
            // ⭐ 两个方向都要判：库里只存一行，书写顺序不可依赖
            if (codes.contains(a) && codes.contains(b)) {
                out.add(a + " ⊗ " + b + "（" + rs.getString("reason") + "）");
            }
        });
        return out;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
