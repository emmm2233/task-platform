package com.dsh.platform.fund;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.fund.dto.EscrowResult;
import com.dsh.platform.fund.service.AcceptanceService;
import com.dsh.platform.fund.service.EscrowService;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.SmsCodeService;
import com.dsh.platform.task.statemachine.TaskStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验收 → 分账：把已有能力串成一条真实链路。
 *
 * <p>⭐ <b>本任务的价值在于"接通"而不是"新增"</b>。
 * 到这个任务为止，各部分都已单独验证过：
 * <ul>
 *   <li>状态机（T10）能判定 {@code PENDING_ACCEPT + ACCEPT → COMPLETED}；</li>
 *   <li>托管与账本（T16）已能收钱入托管池；</li>
 *   <li>分账编排（T17）已能按 {@code escrow_share} 逐份额释放。</li>
 * </ul>
 * 但<b>没有任何东西把它们连起来</b> —— {@code SettlementService} 一个调用方都没有。
 * 一个功能齐全却没人调用的服务，等于没做。
 *
 * <p>链路：验收通过 → 任务转 COMPLETED → 确保份额存在 → 分账 → 接单方到账、托管单 RELEASED。
 *
 * <p>⭐ 两个必须守住的点：
 * <ol>
 *   <li><b>只有当前接单方与发单方能触发验收</b>（防第三方替人验收）；</li>
 *   <li><b>分账失败要能被发现</b>：验收通过但钱没放出去，是最坏的状态 ——
 *       用户看到"已完成"，钱却还在托管池。所以本方法在同一事务里完成，
 *       ⛔ 不允许"状态改了、分账失败了"这种半成品。</li>
 * </ol>
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcceptanceServiceTest {

    private static final Random RANDOM = new Random();

    @Autowired private AcceptanceService acceptanceService;
    @Autowired private EscrowService escrowService;
    @Autowired private AuthService authService;
    @Autowired private SmsCodeService smsCodeService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private Long publisherId;
    private Long takerId;

    @BeforeAll
    void setUp() {
        var keys = redis.keys("sms:limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        publisherId = newUser("发单方");
        takerId = newUser("接单方");
    }

    private Long newUser(String nickname) {
        String phone = "138" + String.format("%08d", RANDOM.nextInt(100_000_000));
        String ip = "10." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(254);
        String code = smsCodeService.send(phone, SmsScene.REGISTER, ip, "dev-" + RANDOM.nextLong());
        return authService.register(phone, "Passw0rd!", nickname, code).getUserId();
    }

    @BeforeEach
    void resetLedger() {
        jdbc.update("DELETE FROM wallet_txn WHERE biz_type IN ('ESCROW_FREEZE','SHARE_RELEASE')");
        jdbc.update("UPDATE wallet_account SET frozen = 0, available = 0, total_in = 0, total_out = 0 "
                + "WHERE account_type = 'ESCROW_HOLD' OR user_id IS NOT NULL");
    }

    /**
     * 造一个"已托管 + 已指派 + 待验收"的任务。
     *
     * ⭐ 走真实链路：建任务 → 托管（产生 biz_order）→ 指派接单方 → 置 PENDING_ACCEPT。
     * ⛔ 不直接往表里塞一个 PENDING_ACCEPT 的行 —— 那样测的不是真实链路，
     * 而"验收时找不到单据"这类问题恰恰只在真实链路里出现。
     */
    private Long seedPendingAccept(String amount) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM task_category WHERE code = 'DEV'", Long.class);
        jdbc.update("""
                INSERT INTO task (task_no, publisher_id, category_id, category_path, task_form, pay_mode,
                                  title, description, grant_mode, award_mode, required_count,
                                  price, budget_min, budget_max, deadline, status, audit_status,
                                  escrow_amount, source_client, version)
                VALUES (?,?,?,'/','ONLINE','BID', ?,?, 'BID','SINGLE',1, ?, 4000.00, 5000.00, ?,
                        'PENDING_ESCROW','PASSED', ?, 'web-user', 0)
                """,
                "A" + Long.toString(System.nanoTime(), 36), publisherId, categoryId,
                "验收分账测试", "验证验收→分账", null,
                LocalDateTime.now().plusDays(20), new BigDecimal(amount));
        Long taskId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        escrowService.fund(taskId, publisherId, new BigDecimal(amount),
                "CH-" + Long.toString(System.nanoTime(), 36), "WECHAT");

        // 指派接单方并推进到待验收（真实链路里由抢单/中标 + 提交交付物完成）
        jdbc.update("UPDATE task SET assignee_id = ?, status = 'PENDING_ACCEPT' WHERE id = ?",
                takerId, taskId);
        return taskId;
    }

    private BigDecimal availableOfTaker() {
        var v = jdbc.queryForList(
                "SELECT available FROM wallet_account WHERE user_id = ? AND account_type = 'USER'",
                BigDecimal.class, takerId);
        return v.isEmpty() ? BigDecimal.ZERO : v.get(0);
    }

    private String escrowStatusOf(Long taskId) {
        return jdbc.queryForObject("""
                SELECT e.status FROM escrow e JOIN biz_order o ON o.id = e.order_id WHERE o.task_id = ?
                """, String.class, taskId);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐⭐ 验收通过 → 任务 COMPLETED、份额释放、接单方到账、托管单 RELEASED")
    void acceptance_releases_funds_to_taker() {
        Long taskId = seedPendingAccept("5000.00");
        BigDecimal before = availableOfTaker();

        acceptanceService.accept(taskId, publisherId, "验收通过");

        // ① 任务状态
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId))
                .isEqualTo(TaskStatus.COMPLETED.name());
        // ② 接单方真的拿到钱 —— 这是整条链路的终点，也是最该验的一件事
        assertThat(availableOfTaker())
                .as("验收通过后钱必须真的到接单方账上")
                .isEqualByComparingTo(before.add(new BigDecimal("5000.00")));
        // ③ 托管单状态如实反映
        assertThat(escrowStatusOf(taskId)).isEqualTo("RELEASED");
        // ④ 份额记录已生成且已释放
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM escrow_share WHERE taker_id = ? AND status = 'RELEASED'",
                Integer.class, takerId)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 只有发单方（或系统自动验收）能触发验收 —— 第三方与接单方都必须被拒")
    void only_parties_can_accept() {
        Long taskId = seedPendingAccept("1000.00");
        Long stranger = newUser("路人");

        assertThatThrownBy(() -> acceptanceService.accept(taskId, stranger, "我来验收"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                // ⚠️ 期望码从 NOT_TASK_OWNER 改为 NO_PERMISSION：
                //    "你不是这条任务的发单方"本质是【鉴权失败】，
                //    且现在接单方也走同一条拒绝分支（见 taker_cannot_accept_its_own_delivery）。
                .isEqualTo(ErrorCode.NO_PERMISSION.getCode());

        // 状态未变
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId))
                .isEqualTo(TaskStatus.PENDING_ACCEPT.name());
    }

    @Test
    @DisplayName("⛔⛔ 接单方【不能】给自己验收 —— 交付与验收由同一方控制等于没有验收")
    void taker_cannot_accept_its_own_delivery() {
        // 修复前这里是 taker_can_also_accept，断言"接单方验收后余额 +800"——
        // 也就是说它把这个漏洞【钉成了预期行为】。
        // 漏洞链路：accept 放行 isTaker → sm.fire 只校验(状态,事件)、operatorType 只写日志
        // ⇒ PENDING_ACCEPT → COMPLETED，并在同一事务里把托管款放给自己。
        // 设计文档：验收触发者只有「发单方 / 自动验收」（§4.11.4 / §4.11.5）。
        Long taskId = seedPendingAccept("800.00");
        BigDecimal before = availableOfTaker();

        assertThatThrownBy(() -> acceptanceService.accept(taskId, takerId, "接单方自己验收"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NO_PERMISSION.getCode());

        assertThat(availableOfTaker())
                .as("被拒的验收一分钱都不能动")
                .isEqualByComparingTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId))
                .as("状态也不能被推进")
                .isEqualTo(TaskStatus.PENDING_ACCEPT.name());
    }

    @Test
    @DisplayName("⭐ 系统自动验收【可以】触发（偏接单方的保护走这条路，而不是让接单方自验）")
    void system_actor_can_accept() {
        Long taskId = seedPendingAccept("700.00");
        BigDecimal before = availableOfTaker();

        acceptanceService.accept(taskId, AcceptanceService.SYSTEM_ACTOR, "交付后 7 天自动验收");

        assertThat(availableOfTaker())
                .as("自动验收后正常放款给接单方")
                .isEqualByComparingTo(before.add(new BigDecimal("700.00")));
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId))
                .isEqualTo(TaskStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("⭐ 状态不对不能验收：履约中直接验收必须被拒，且不动任何钱")
    void cannot_accept_before_delivery() {
        Long taskId = seedPendingAccept("600.00");
        jdbc.update("UPDATE task SET status = 'IN_PROGRESS' WHERE id = ?", taskId);
        BigDecimal before = availableOfTaker();

        assertThatThrownBy(() -> acceptanceService.accept(taskId, publisherId, "提前验收"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TASK_STATUS_INVALID.getCode());

        assertThat(availableOfTaker())
                .as("被拒的验收不能让钱动")
                .isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("⭐ 重复验收不重复放款（幂等：弱网重试 / 自动验收与人工验收撞车）")
    void duplicate_acceptance_does_not_pay_twice() {
        Long taskId = seedPendingAccept("2000.00");
        acceptanceService.accept(taskId, publisherId, "第一次");
        BigDecimal afterFirst = availableOfTaker();

        // 第二次：状态已是 COMPLETED，状态机会拒绝；关键是钱不能再动
        try {
            acceptanceService.accept(taskId, publisherId, "第二次");
        } catch (BizException ignored) {
            // 状态机拒绝是预期行为之一
        }

        assertThat(availableOfTaker())
                .as("重复验收不能把钱付两次")
                .isEqualByComparingTo(afterFirst);
    }
}
