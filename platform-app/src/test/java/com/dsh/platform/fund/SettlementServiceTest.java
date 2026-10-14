package com.dsh.platform.fund;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.fund.dto.EscrowResult;
import com.dsh.platform.fund.dto.SettleResult;
import com.dsh.platform.fund.service.EscrowService;
import com.dsh.platform.fund.service.SettlementService;
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
 * T17 · 分账编排。
 *
 * <p>⭐ <b>分账要解决的核心问题是"钱按什么依据分"</b>。
 * 依据是 {@code escrow_share} —— 每个结算单元（Phase 1 一律一个 WHOLE 单元）
 * 一条份额记录。分账 = 逐份额把托管池的钱放给对应的接单方。
 *
 * <p>四条不变量：
 * <ol>
 *   <li>⭐⭐ <b>配平</b>：{@code Σ share_amount == escrow.amount}。
 *       少了 → 有份额永远释放不了，钱卡在托管池（平台占着别人的钱）；
 *       多了 → 超发。这条必须在<b>分账开始前</b>整体校验，而不是边分边发现；</li>
 *   <li>⭐ <b>幂等/可重入</b>：分账可能中途失败重跑，
 *       已释放的份额不能重复放款；</li>
 *   <li>⭐ <b>状态如实反映进度</b>：全部释放 → {@code RELEASED}；
 *       只释放了一部分 → {@code PARTIAL_RELEASED}。
 *       ⛔ 不能一律写 {@code RELEASED}，否则对账时看不出还有钱没放出去；</li>
 *   <li>⭐ <b>账本与单据一致</b>：分账完成后托管池 {@code frozen} 归零。
 *       两处不一致是最难查的问题 —— 单据说分完了、账本说还有钱。</li>
 * </ol>
 *
 * <p>⚠️ 库层还有一道兜底：{@code ck_share_sum} 保证
 * {@code released + refunded + frozen <= share_amount}，
 * 单条份额超放会被数据库直接拦下。但那是最后一道 ——
 * 配平要在业务层先校验，才能给出可读的原因。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettlementServiceTest {

    private static final Random RANDOM = new Random();

    @Autowired private SettlementService settlementService;
    @Autowired private EscrowService escrowService;
    @Autowired private AuthService authService;
    @Autowired private SmsCodeService smsCodeService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private Long publisherId;
    private Long takerA;
    private Long takerB;

    @BeforeAll
    void setUp() {
        var keys = redis.keys("sms:limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        publisherId = newUser("发单方");
        takerA = newUser("接单方A");
        takerB = newUser("接单方B");
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

    /** 造一个已托管的订单：任务 → 审核 → 托管（真实链路，不绕过 EscrowService）。 */
    private EscrowResult fund(String amount) {
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
                "S" + Long.toString(System.nanoTime(), 36), publisherId, categoryId,
                "分账测试", "验证分账", null, LocalDateTime.now().plusDays(20), new BigDecimal(amount));
        Long taskId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return escrowService.fund(taskId, publisherId, new BigDecimal(amount),
                "CH-" + Long.toString(System.nanoTime(), 36), "WECHAT");
    }

    /** 给订单加一条份额。 */
    private Long addShare(Long orderId, Long escrowId, Long takerId, String amount) {
        jdbc.update("""
                INSERT INTO escrow_share (escrow_id, order_id, sub_order_id, unit_id, taker_id,
                                          share_amount, status, created_at, updated_at)
                VALUES (?,?,?,?,?,?, 'PENDING', NOW(3), NOW(3))
                """,
                escrowId, orderId, RANDOM.nextInt(100000) + 1, RANDOM.nextInt(100000) + 1,
                takerId, new BigDecimal(amount));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private BigDecimal frozenOfHold() {
        return jdbc.queryForObject(
                "SELECT frozen FROM wallet_account WHERE account_type = 'ESCROW_HOLD' AND user_id IS NULL",
                BigDecimal.class);
    }

    private BigDecimal availableOf(Long userId) {
        var ids = jdbc.queryForList(
                "SELECT available FROM wallet_account WHERE user_id = ? AND account_type = 'USER'",
                BigDecimal.class, userId);
        return ids.isEmpty() ? BigDecimal.ZERO : ids.get(0);
    }

    private String escrowStatus(Long escrowId) {
        return jdbc.queryForObject("SELECT status FROM escrow WHERE id = ?", String.class, escrowId);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 配平后全量释放：份额全部 RELEASED、托管池冻结归零、escrow 变 RELEASED")
    void balanced_settlement_releases_all_shares() {
        EscrowResult e = fund("5000.00");
        addShare(e.getOrderId(), e.getEscrowId(), takerA, "3000.00");
        addShare(e.getOrderId(), e.getEscrowId(), takerB, "2000.00");

        SettleResult r = settlementService.settle(e.getOrderId(), e.getEscrowId());

        assertThat(r.getReleasedCount()).isEqualTo(2);
        assertThat(r.getReleasedAmount()).isEqualByComparingTo("5000.00");
        assertThat(r.getStatus()).isEqualTo("RELEASED");
        assertThat(escrowStatus(e.getEscrowId())).isEqualTo("RELEASED");

        assertThat(availableOf(takerA)).isEqualByComparingTo("3000.00");
        assertThat(availableOf(takerB)).isEqualByComparingTo("2000.00");

        // ⭐ 账本与单据一致：托管池归零
        assertThat(frozenOfHold())
                .as("分账完成后托管池不该还剩钱 —— 单据说分完了、账本说还有钱是最难查的问题")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM escrow_share WHERE escrow_id = ? AND status = 'RELEASED'",
                Integer.class, e.getEscrowId())).isEqualTo(2);
    }

    @Test
    @DisplayName("⭐⭐ 不配平必须被拒：Σ份额 < 托管额（否则钱永远卡在托管池）")
    void unbalanced_settlement_is_rejected() {
        EscrowResult e = fund("5000.00");
        addShare(e.getOrderId(), e.getEscrowId(), takerA, "3000.00");   // 只配了 3000

        assertThatThrownBy(() -> settlementService.settle(e.getOrderId(), e.getEscrowId()))
                .as("少配 2000，那部分钱会永远卡在托管池 —— 平台占着别人的钱")
                .isInstanceOf(BizException.class)
                .extracting(x -> ((BizException) x).getCode())
                .isEqualTo(ErrorCode.AMOUNT_MISMATCH.getCode());

        // ⛔ 被拒时不能已经开始放款
        assertThat(frozenOfHold()).isEqualByComparingTo("5000.00");
        assertThat(availableOf(takerA)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("⭐⭐ 超配也必须被拒：Σ份额 > 托管额（不能让平台垫付）")
    void over_allocated_settlement_is_rejected() {
        EscrowResult e = fund("5000.00");
        addShare(e.getOrderId(), e.getEscrowId(), takerA, "4000.00");
        addShare(e.getOrderId(), e.getEscrowId(), takerB, "2000.00");   // 合计 6000 > 5000

        assertThatThrownBy(() -> settlementService.settle(e.getOrderId(), e.getEscrowId()))
                .isInstanceOf(BizException.class)
                .extracting(x -> ((BizException) x).getCode())
                .isEqualTo(ErrorCode.AMOUNT_MISMATCH.getCode());

        assertThat(frozenOfHold()).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("⭐ 可重入：重跑不重复放款（分账中途失败重试是常态）")
    void settlement_is_idempotent_on_rerun() {
        EscrowResult e = fund("3000.00");
        addShare(e.getOrderId(), e.getEscrowId(), takerA, "3000.00");

        settlementService.settle(e.getOrderId(), e.getEscrowId());
        BigDecimal afterFirst = availableOf(takerA);
        settlementService.settle(e.getOrderId(), e.getEscrowId());

        assertThat(availableOf(takerA))
                .as("重跑不能把钱付两次")
                .isEqualByComparingTo(afterFirst);
        assertThat(frozenOfHold()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_txn WHERE biz_type = 'SHARE_RELEASE' AND escrow_id = ?",
                Integer.class, e.getEscrowId()))
                .as("同一份额只能有一条释放分录")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 部分释放：只放一部分份额时 escrow 是 PARTIAL_RELEASED 而不是 RELEASED")
    void partial_release_reflects_true_progress() {
        EscrowResult e = fund("5000.00");
        Long shareA = addShare(e.getOrderId(), e.getEscrowId(), takerA, "2000.00");
        addShare(e.getOrderId(), e.getEscrowId(), takerB, "3000.00");

        // 只释放 A 那一条
        SettleResult r = settlementService.settleShare(e.getEscrowId(), shareA);

        assertThat(r.getStatus()).isEqualTo("PARTIAL_RELEASED");
        assertThat(escrowStatus(e.getEscrowId()))
                .as("还有 3000 没放出去，不能报成 RELEASED —— 否则对账看不出余额")
                .isEqualTo("PARTIAL_RELEASED");
        assertThat(availableOf(takerA)).isEqualByComparingTo("2000.00");
        assertThat(frozenOfHold()).isEqualByComparingTo("3000.00");
    }

    @Test
    @DisplayName("⭐ 争议冻结的份额不参与分账（DISPUTE_FROZEN 必须被跳过）")
    void dispute_frozen_shares_are_skipped() {
        EscrowResult e = fund("3000.00");
        Long shareOk = addShare(e.getOrderId(), e.getEscrowId(), takerA, "1000.00");
        Long shareFrozen = addShare(e.getOrderId(), e.getEscrowId(), takerB, "2000.00");
        jdbc.update("UPDATE escrow_share SET status = 'DISPUTE_FROZEN', frozen_amount = 2000.00 "
                + "WHERE id = ?", shareFrozen);

        SettleResult r = settlementService.settle(e.getOrderId(), e.getEscrowId());

        assertThat(r.getReleasedCount()).as("只放非争议的那条").isEqualTo(1);
        assertThat(availableOf(takerA)).isEqualByComparingTo("1000.00");
        assertThat(availableOf(takerB)).as("争议中的钱不能放出去").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(frozenOfHold()).isEqualByComparingTo("2000.00");
        assertThat(r.getStatus()).isEqualTo("PARTIAL_RELEASED");
    }
}
