package com.dsh.platform.identity.security;

import java.util.List;

/**
 * 数据范围（第三层鉴权的判定依据）。
 *
 * <p>三层鉴权各挡一类越权：
 * <ol>
 *   <li><b>路径域</b> —— 垂直越权：普通用户不能碰 /admin/**</li>
 *   <li><b>权限点</b> —— 同域内越权：审核员不能冻结账号</li>
 *   <li><b>数据范围</b>（本类）—— 水平越权：A 看不到 B 的数据</li>
 * </ol>
 *
 * <p>⭐ 关键设计：<b>可见性判定只有这一个出口</b>。
 * 如果让每个 Service 自己写 {@code if (order.getUserId().equals(me))}，
 * 几十个查询里迟早漏一个 —— 而漏掉的那一个就是数据泄露。
 */
public final class DataScope {

    /** 范围：只能看自己的数据。 */
    public static final String SELF = "SELF";

    /** 范围：可看全部（仅运营岗）。 */
    public static final String ALL = "ALL";

    private DataScope() {
    }

    /**
     * 当前请求主体。
     *
     * @param userId         用户 id
     * @param identity       平台身份 USER / OPERATOR / ADMIN
     * @param scope          数据范围 SELF / ALL（由角色解析得出，多角色取最宽）
     * @param realNameStatus 实名状态（权益出口的输入之一）
     */
    public record Principal(Long userId, String identity, String scope, String realNameStatus) {

        public boolean isAllScope() {
            return ALL.equals(scope);
        }

        public boolean isOperator() {
            return "OPERATOR".equals(identity) || "ADMIN".equals(identity);
        }

        public boolean isAdmin() {
            return "ADMIN".equals(identity);
        }
    }

    /**
     * 判定"当前主体能否看见属于 {@code ownerId} 的这条记录"。
     *
     * <p>⛔ 所有按归属过滤的地方都必须调它，不要各自写条件判断。
     */
    public static boolean canSee(Principal principal, Long ownerId) {
        if (principal == null || ownerId == null) {
            return false;
        }
        if (principal.isAllScope()) {
            return true;
        }
        return ownerId.equals(principal.userId());
    }

    /**
     * 多角色合并：取<b>最宽</b>的范围。
     *
     * <p>例：既是 ROLE_USER(SELF) 又是 ROLE_AUDITOR(ALL) → 取 ALL。
     * ⛔ 反过来取最窄会导致运营岗看不到任何数据（功能不可用）。
     */
    public static String mergeScope(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return SELF;
        }
        return scopes.contains(ALL) ? ALL : SELF;
    }
}
