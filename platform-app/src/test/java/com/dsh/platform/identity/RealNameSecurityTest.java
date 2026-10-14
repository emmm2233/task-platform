package com.dsh.platform.identity;

import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.identity.dto.LoginResult;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.RoleService;
import com.dsh.platform.identity.service.SmsCodeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T6 · 实名认证（L4 数据）安全测试。
 *
 * <p>L4 是全平台最敏感的数据（身份证号 + 证件照）。本测试盯三件事：
 * <ol>
 *   <li><b>静态加密</b> —— 库里必须是密文，且密文里搜不到明文片段；</li>
 *   <li><b>访问留痕</b> —— 看一眼就要记一笔（等保三级：重要数据访问可追溯）；</li>
 *   <li><b>职责隔离</b> —— 内容审核员即使同属运营域，也拿不到 L4。</li>
 * </ol>
 *
 * <p>⚠️ 两条测试纪律（都踩过坑）：
 * <ul>
 *   <li>身份证号必须<b>随机</b>：硬编码会被「一人一号」校验正确拦下，且跨运行重复。
 *       这与 T4 的「Redis 限流计数跨运行残留」同源 —— 测试数据必须与持久状态解耦。</li>
 *   <li>请求体必须用 {@link #realNameJson} 统一构造，⛔ 不要在每个用例里手写 JSON 字面量：
 *       三份相似的字面量必然漂移；本测试第一版就有一处漏了 {@code .formatted()}，
 *       结果把字面量 {@code %s} 当成身份证号发出去，静默失败。</li>
 * </ul>
 */
@SpringBootTest
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureMockMvc
class RealNameSecurityTest {

    @Autowired
    private StringRedisTemplate redis;

    /**
     * ⭐ 清掉限流计数。
     *
     * <p>限流状态存在 Redis，TTL 2 小时 —— 它<b>跨测试类、跨运行都活着</b>。
     * 不清理的话，第二次跑测试就会撞上一轮留下的计数（本测试踩过两次：
     * 一次是手机号由 SEQ 递推导致每轮同一批号码，一次是随机 IP 恰好撞车）。
     * 用随机值只能降低概率，<b>确定性清理才根治</b>。
     */
    @BeforeAll
    void clearRateLimitCounters() {
        var keys = redis.keys("sms:limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private static final Random RANDOM = new Random();

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired
    private AuthService authService;

    @Autowired
    private SmsCodeService smsCodeService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** 随机合法身份证号：17 位数字 + X。 */
    private static String randomIdCard() {
        return "410101" + String.format("%011d", RANDOM.nextLong(100_000_000_000L)) + "X";
    }

    /** 与 RealNameService 的掩码规则一致：保留前 3 后 4。 */
    private static String expectedMask(String idCard) {
        return idCard.substring(0, 3) + "*".repeat(idCard.length() - 7)
                + idCard.substring(idCard.length() - 4);
    }

    /** ⭐ 请求体统一构造 —— 用 Jackson 序列化，而不是手写字符串拼接。 */
    private static String realNameJson(String name, String idCard, String suffix) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("realType", "PERSONAL");
        body.put("realName", name);
        body.put("idCardNo", idCard);
        body.put("idCardFront", "realname/f" + suffix + ".jpg");
        body.put("idCardBack", "realname/b" + suffix + ".jpg");
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomPhone(String prefix) {
        return prefix + String.format("%08d", RANDOM.nextInt(100_000_000));
    }

    private static String randomIp() {
        return "10." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(254);
    }

    private LoginResult newUser() {
        String phone = randomPhone("138");
        String code = smsCodeService.send(phone, SmsScene.REGISTER, randomIp(), "dev-" + RANDOM.nextLong());
        return authService.register(phone, "Passw0rd!", "用户" + RANDOM.nextInt(1000), code);
    }

    /** 造一个带指定岗位的运营账号，返回可用的访问令牌。 */
    private String operatorWith(String roleCode) {
        String phone = randomPhone("137");
        String code = smsCodeService.send(phone, SmsScene.REGISTER, randomIp(), "dev-" + RANDOM.nextLong());
        LoginResult u = authService.register(phone, "Passw0rd!", "运营" + RANDOM.nextInt(1000), code);

        roleService.assignRoles(u.getUserId(), List.of(roleCode), 1L, "测试");
        jdbc.update("UPDATE sys_user SET identity = 'OPERATOR' WHERE id = ?", u.getUserId());
        return authService.login(phone, "Passw0rd!").getAccessToken();
    }

    /** 提交一份实名，返回 real_name 主键。 */
    private Long submitRealName(LoginResult user, String name, String idCard, String suffix) throws Exception {
        mockMvc.perform(post("/api/v1/account/real-name")
                        .header("Authorization", "Bearer " + user.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(realNameJson(name, idCard, suffix)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        return jdbc.queryForObject(
                "SELECT id FROM sys_real_name WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, user.getUserId());
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 身份证号静态加密：库里是密文，密文里搜不到明文片段")
    void id_card_is_encrypted_at_rest() throws Exception {
        LoginResult u = newUser();
        String idCard = randomIdCard();
        submitRealName(u, "张三", idCard, "1");

        String enc = jdbc.queryForObject(
                "SELECT id_card_enc FROM sys_real_name WHERE user_id = ?", String.class, u.getUserId());

        assertThat(enc).isNotEqualTo(idCard);
        // 密文里搜不到明文片段 —— 防"只是做了 Base64"这种伪加密
        assertThat(enc).doesNotContain(idCard.substring(0, 6));
        assertThat(enc).doesNotContain(idCard.substring(6, 14));
        // 带版本前缀，为将来换算法留路
        assertThat(enc).startsWith("v1:");

        // 掩码列可直接对外展示，不需要解密
        String masked = jdbc.queryForObject(
                "SELECT id_card_masked FROM sys_real_name WHERE user_id = ?", String.class, u.getUserId());
        assertThat(masked).isEqualTo(expectedMask(idCard));

        // 盲索引：64 位十六进制，不可反查
        String hash = jdbc.queryForObject(
                "SELECT id_card_hash FROM sys_real_name WHERE user_id = ?", String.class, u.getUserId());
        assertThat(hash).hasSize(64).doesNotContain(idCard.substring(0, 6));
    }

    @Test
    @DisplayName("⭐ 看一眼就记一笔：查看 L4 必须写访问审计")
    void viewing_l4_data_writes_access_audit() throws Exception {
        String idCard = randomIdCard();
        LoginResult applicant = newUser();
        Long realNameId = submitRealName(applicant, "李四", idCard, "2");

        String rnToken = operatorWith("ROLE_RN_AUDITOR");
        mockMvc.perform(get("/api/v1/op/real-name-audits/" + realNameId)
                        .header("Authorization", "Bearer " + rnToken))
                .andExpect(status().isOk())
                // ⛔ 响应里不得有明文身份证号
                .andExpect(jsonPath("$.data.idCardNo").doesNotExist())
                .andExpect(jsonPath("$.data.idCardMasked").value(expectedMask(idCard)))
                // ⭐ 水印：谁在什么时候看的，用于追责
                .andExpect(jsonPath("$.data.watermark").isNotEmpty());

        Integer logs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_data_access_log WHERE target_user = ? AND target_type = 'REAL_NAME'",
                Integer.class, applicant.getUserId());
        assertThat(logs)
                .as("⭐ 查看 L4 数据必须留下访问审计（等保三级：重要数据访问可追溯）")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 职责隔离：内容审核员拿不到 L4（即使同属运营域）")
    void content_auditor_cannot_view_raw_id_card() throws Exception {
        String idCard = randomIdCard();
        LoginResult applicant = newUser();
        Long realNameId = submitRealName(applicant, "王五", idCard, "3");

        // 内容审核员有 task:audit / task:takedown，但**没有** realname:audit
        String contentAuditorToken = operatorWith("ROLE_AUDITOR");
        mockMvc.perform(get("/api/v1/op/real-name-audits/" + realNameId)
                        .header("Authorization", "Bearer " + contentAuditorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_PERMISSION.getCode()));
    }

    @Test
    @DisplayName("提交后状态 PENDING，且用户只能看到自己的实名记录")
    void submit_sets_pending_and_is_scoped_to_self() throws Exception {
        String idCard = randomIdCard();
        LoginResult a = newUser();
        LoginResult b = newUser();
        submitRealName(a, "赵六", idCard, "4");

        assertThat(jdbc.queryForObject(
                "SELECT real_name_status FROM sys_user WHERE id = ?", String.class, a.getUserId()))
                .isEqualTo("PENDING");

        // b 查自己的实名状态 → 未提交；⛔ 拿不到 a 的
        mockMvc.perform(get("/api/v1/account/real-name")
                        .header("Authorization", "Bearer " + b.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNSUBMITTED"))
                .andExpect(jsonPath("$.data.idCardMasked").doesNotExist());
    }
}
