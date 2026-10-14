package com.dsh.platform.identity;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.identity.dto.LoginResult;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.security.DataScope;
import com.dsh.platform.identity.service.RoleService;
import com.dsh.platform.identity.service.SmsCodeService;
import com.dsh.platform.identity.service.UserDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T5 · RBAC 三层鉴权：路径域 / 权限点 / 数据范围 + 岗位互斥。
 *
 * <p>三层各挡一类越权：
 * <ol>
 *   <li><b>路径域</b> —— 挡<b>垂直越权</b>：普通用户不能碰 /admin/**</li>
 *   <li><b>权限点</b> —— 挡<b>同域内的越权</b>：审核员不能执行冻结账号</li>
 *   <li><b>数据范围</b> —— 挡<b>水平越权</b>：A 看不到 B 的数据</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoleConflictTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Random RANDOM = new Random();

    @Autowired
    private RoleService roleService;

    @Autowired
    private UserDirectoryService userDirectoryService;

    @Autowired
    private SmsCodeService smsCodeService;

    @Autowired
    private com.dsh.platform.identity.service.AuthService authService;

    @Autowired
    private JdbcTemplate jdbc;

    private String phone;
    private LoginResult me;

    @BeforeEach
    void setUp() {
        phone = "138" + String.format("%08d", RANDOM.nextInt(100_000_000));
        String ip = "10." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(254);
        String code = smsCodeService.send(phone, SmsScene.REGISTER, ip, "dev-" + RANDOM.nextLong());
        me = authService.register(phone, "Passw0rd!", "小张", code);
    }

    @Test
    @DisplayName("⭐ 互斥岗位不可同时分配（ROLE_RISK_ADMIN ⊗ ROLE_ARBITER）")
    void assigning_mutually_exclusive_roles_is_rejected() {
        assertThatThrownBy(() -> roleService.assignRoles(me.getUserId(),
                List.of("ROLE_RISK_ADMIN", "ROLE_ARBITER"), 1L, "测试"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROLE_CONFLICT.getCode());
    }

    @Test
    @DisplayName("⭐ 互斥是<b>对称</b>的：反过来写也必须被拒（表里只存了一行）")
    void role_conflict_is_symmetric() {
        // sys_role_conflict 只存了 (ROLE_RISK_ADMIN, ROLE_ARBITER) 一行；
        // 若检测只比对单向，把顺序倒过来就能绕过 —— 这是最容易被写错的地方。
        assertThatThrownBy(() -> roleService.assignRoles(me.getUserId(),
                List.of("ROLE_ARBITER", "ROLE_RISK_ADMIN"), 1L, "测试"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROLE_CONFLICT.getCode());
    }

    @Test
    @DisplayName("非互斥岗位可以正常分配，且权限版本自增（使旧令牌立即失效）")
    void compatible_roles_can_be_assigned_and_bump_permission_version() {
        Integer before = jdbc.queryForObject(
                "SELECT permission_version FROM sys_user WHERE id = ?", Integer.class, me.getUserId());

        roleService.assignRoles(me.getUserId(), List.of("ROLE_AUDITOR", "ROLE_SUPPORT"), 1L, "测试");

        Integer after = jdbc.queryForObject(
                "SELECT permission_version FROM sys_user WHERE id = ?", Integer.class, me.getUserId());
        assertThat(after).isEqualTo(before + 1);

        List<String> codes = jdbc.queryForList(
                "SELECT r.role_code FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id WHERE ur.user_id = ?",
                String.class, me.getUserId());
        assertThat(codes).containsExactlyInAnyOrder("ROLE_AUDITOR", "ROLE_SUPPORT");
    }

    @Test
    @DisplayName("分配不存在的岗位被拒（防止写坏 sys_user_role 造成权限空洞）")
    void unknown_role_code_is_rejected() {
        assertThatThrownBy(() -> roleService.assignRoles(me.getUserId(),
                List.of("ROLE_NO_SUCH_ROLE"), 1L, "测试"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("⭐ 数据范围 SELF：用户查不到别人的记录（水平越权）")
    void self_scoped_user_cannot_see_another_users_row() {
        // me 是 SELF 范围；再建一个用户
        String otherPhone = "139" + String.format("%08d", RANDOM.nextInt(100_000_000));
        String ip = "10." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(254);
        String code = smsCodeService.send(otherPhone, SmsScene.REGISTER, ip, "dev-" + RANDOM.nextLong());
        LoginResult other = authService.register(otherPhone, "Passw0rd!", "小李", code);

        // 以 me 的身份（SELF）查 other → 取不到
        assertThat(userDirectoryService.findVisible(other.getUserId(), principal(me, "SELF")))
                .as("SELF 范围的用户不能看到别人的记录")
                .isNull();

        // 以 ALL 范围的运营身份查 → 能取到
        assertThat(userDirectoryService.findVisible(other.getUserId(), principal(me, "ALL")))
                .as("ALL 范围（运营岗）可以看到")
                .isNotNull();
    }

    @Test
    @DisplayName("⭐ 数据范围 SELF：查自己的记录正常可见（别把功能也挡了）")
    void self_scoped_user_can_see_own_row() {
        assertThat(userDirectoryService.findVisible(me.getUserId(), principal(me, "SELF")))
                .isNotNull();
    }

    @Test
    @DisplayName("实名状态未提交的用户被标记为受限（权益出口的输入之一）")
    void unsubmitted_real_name_is_reflected_in_principal() {
        DataScope.Principal p = principal(me, "SELF");
        assertThat(p.realNameStatus()).isEqualTo("UNSUBMITTED");
        assertThat(p.identity()).isEqualTo("USER");
    }

    @Test
    @DisplayName("⭐ 垂直越权：操作员令牌访问 /api/v1/admin/** 必须 403（第 1 层：路径域）")
    void operator_cannot_reach_admin_path() throws Exception {
        roleService.assignRoles(me.getUserId(), List.of("ROLE_AUDITOR"), 1L, "测试");
        jdbc.update("UPDATE sys_user SET identity = 'OPERATOR' WHERE id = ?", me.getUserId());
        LoginResult op = authService.login(phone, "Passw0rd!");   // 重新登录拿带新 pv 的令牌

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + op.getAccessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("⭐ 普通用户令牌访问 /api/v1/op/** 同样 403（运营区也不是想进就能进）")
    void plain_user_cannot_reach_operator_path() throws Exception {
        mockMvc.perform(get("/api/v1/op/tasks").header("Authorization", "Bearer " + me.getAccessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("无令牌一律 401，且响应体是统一形态（四端可用同一套逻辑处理登录失效）")
    void missing_token_is_401_with_unified_body() throws Exception {
        mockMvc.perform(get("/api/v1/account/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN.getCode()));
    }

    @Test
    @DisplayName("⭐ 改岗位后旧令牌立即失效（权限版本 pv 比对，不必等令牌过期）")
    void old_token_is_invalidated_after_role_change() throws Exception {
        String oldToken = me.getAccessToken();
        // 先确认旧令牌可用
        mockMvc.perform(get("/api/v1/account/profile").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(me.getUserId()));

        roleService.assignRoles(me.getUserId(), List.of("ROLE_AUDITOR"), 1L, "测试");   // pv +1

        // 旧令牌带着过期的 pv → 认证被拒 → 401
        mockMvc.perform(get("/api/v1/account/profile").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
    }

    private DataScope.Principal principal(LoginResult r, String scope) {
        return new DataScope.Principal(r.getUserId(), r.getIdentity(), scope, r.getRealNameStatus());
    }
}
