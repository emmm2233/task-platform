package com.dsh.platform.fund;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.fund.dto.EscrowResult;
import com.dsh.platform.fund.service.EscrowService;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.SmsCodeService;
import com.dsh.platform.task.statemachine.TaskStatus;
import org.junit.jupiter.api.BeforeAll;
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
 * T16 · 托管（资金链路的第一环）。
 *
 * <p>托管 = 发单方把钱<b>冻结在第三方渠道</b>，等验收通过才放给接单方。
 * 这是整个平台最重要的信任机制：没有它，接单方不敢先干活、发单方不敢先付钱。
 *
 * <p>四条必须守住：
 * <ol>
 *   <li>⭐ <b>金额必须与任务托管额一致</b> —— 少付了后面分账不够分；
 *       多付了平台要退，而"退多付的钱"是最容易出错的资金分支；</li>
 *   <li>⭐ <b>幂等</b> —— 渠道会重复回调（超时重推是常态）。
 *       以 {@code channel_order_no} 为幂等键，重复回调只记一次；</li>
 *   <li><b>只有发单方能托管自己的任务</b>；</li>
 *   <li><b>状态从 PENDING_ESCROW 按 grant_mode 分流</b> —— 抢单进 RECRUITING，
 *       竞拍进 BIDDING。这正是状态机把「→ RECRUITING / BIDDING」拆成两个事件的原因。</li>
 * </ol>
 *
 * <p>⚠️ 账本：库里<b>有</b> {@code wallet_account}（含 ESCROW_HOLD 托管池科目）
 * 与 {@code wallet_txn}（复式记账）。渠道是资金变动的权威来源，账本是平台侧镜像。
 * 本类只验 {@code biz_order} + {@code escrow}；<b>写账本尚未实现</b>（T17 前置）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EscrowServiceTest {

    private static final Random RANDOM = new Random();

    @Autowired private EscrowService escrowService;
    @Autowired private AuthService authService;
    @Autowired private SmsCodeService smsCodeService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private Long publisherId;

    @BeforeAll
    void setUp() {
        var keys = redis.keys("sms:limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        String phone = "138" + String.format("%08d", RANDOM.nextInt(100_000_000));
        String ip = "10." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(254);
        String code = smsCodeService.send(phone, SmsScene.REGISTER, ip, "dev-" + RANDOM.nextLong());
        publisherId = authService.register(phone, "Passw0rd!", "发单方", code).getUserId();
    }

    /** 造一个 PENDING_ESCROW 的任务，托管额 5000。 */
    private Long seedPendingEscrow(String payMode, String grantMode) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM task_category WHERE code = 'DEV'", Long.class);
        jdbc.update("""
                INSERT INTO task (task_no, publisher_id, category_id, category_path, task_form, pay_mode,
                                  title, description, grant_mode, award_mode, required_count,
                                  price, budget_min, budget_max, deadline, status, audit_status,
                                  escrow_amount, source_client, version)
                VALUES (?,?,?,'/','ONLINE',?, ?,?, ?, 'SINGLE', 1,
                        ?, 4000.00, 5000.00, ?, 'PENDING_ESCROW', 'PASSED',
                        5000.00, 'web-user', 0)
                """,
                "E" + Long.toString(System.nanoTime(), 36), publisherId, categoryId,
                payMode, "托管测试", "验证托管规则", grantMode,
                "BID".equals(payMode) ? null : new BigDecimal("5000.00"),
                LocalDateTime.now().plusDays(20));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 托管成功：建 biz_order + escrow，任务按 grant_mode 进 BIDDING")
    void escrow_creates_order_and_moves_task() {
        Long taskId = seedPendingEscrow("BID", "BID");

        EscrowResult r = escrowService.fund(taskId, publisherId,
                new BigDecimal("5000.00"), "CH-001-" + RANDOM.nextInt(1_000_000), "WECHAT");

        assertThat(r.getOrderId()).isNotNull();
        assertThat(r.getEscrowId()).isNotNull();
        assertThat(r.getStatus()).isEqualTo("FROZEN");
        assertThat(r.isIdempotent()).isFalse();

        // 任务推进到 BIDDING（竞拍）
        assertThat(jdbc.queryForObject(
                "SELECT status FROM task WHERE id = ?", String.class, taskId))
                .isEqualTo(TaskStatus.BIDDING.name());

        // biz_order 与 escrow 都落了库且金额一致
        assertThat(jdbc.queryForObject(
                "SELECT total_amount FROM biz_order WHERE id = ?", BigDecimal.class, r.getOrderId()))
                .isEqualByComparingTo("5000.00");
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM escrow WHERE id = ?", BigDecimal.class, r.getEscrowId()))
                .isEqualByComparingTo("5000.00");

        // ⭐ 账本必须同步记一笔：escrow 表只说明"这笔钱托管了"，
        //    账本才回答"平台账上现在有多少托管中的钱"。两者脱节则对账无从做起。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_txn WHERE escrow_id = ? AND biz_type = 'ESCROW_FREEZE'",
                Integer.class, r.getEscrowId()))
                .as("托管成功必须在账本留下冻结分录")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT frozen FROM wallet_account WHERE account_type = 'ESCROW_HOLD' AND user_id IS NULL",
                BigDecimal.class))
                .as("托管池 frozen 应包含这笔托管")
                .isGreaterThanOrEqualTo(new BigDecimal("5000.00"));
    }

    @Test
    @DisplayName("⭐ 幂等重推时不重复入账（账本不能因为渠道重推把托管池加两次）")
    void ledger_is_not_duplicated_on_idempotent_retry() {
        Long taskId = seedPendingEscrow("BID", "BID");
        String channelNo = "CH-ledger-" + RANDOM.nextInt(1_000_000);

        EscrowResult first = escrowService.fund(taskId, publisherId,
                new BigDecimal("5000.00"), channelNo, "WECHAT");
        BigDecimal frozenAfterFirst = jdbc.queryForObject(
                "SELECT frozen FROM wallet_account WHERE account_type = 'ESCROW_HOLD' AND user_id IS NULL",
                BigDecimal.class);

        escrowService.fund(taskId, publisherId, new BigDecimal("5000.00"), channelNo, "WECHAT");

        assertThat(jdbc.queryForObject(
                "SELECT frozen FROM wallet_account WHERE account_type = 'ESCROW_HOLD' AND user_id IS NULL",
                BigDecimal.class))
                .as("渠道重推不能让托管池凭空多一笔")
                .isEqualByComparingTo(frozenAfterFirst);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_txn WHERE escrow_id = ?", Integer.class, first.getEscrowId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 抢单模式托管后进 RECRUITING（状态机按 grant_mode 分流）")
    void grab_task_goes_to_recruiting() {
        Long taskId = seedPendingEscrow("FIXED", "GRAB");

        escrowService.fund(taskId, publisherId, new BigDecimal("5000.00"),
                "CH-002-" + RANDOM.nextInt(1_000_000), "ALIPAY");

        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId))
                .isEqualTo(TaskStatus.RECRUITING.name());
    }

    @Test
    @DisplayName("⭐ 金额必须与托管额一致：少付多付都拒（多付的退款是最容易出错的资金分支）")
    void amount_must_match_exactly() {
        Long taskId = seedPendingEscrow("BID", "BID");

        assertThatThrownBy(() -> escrowService.fund(taskId, publisherId,
                new BigDecimal("4000.00"), "CH-short-" + RANDOM.nextInt(1_000_000), "WECHAT"))
                .as("托管额 5000，只付 4000 后面分账不够分")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.AMOUNT_MISMATCH.getCode());

        assertThatThrownBy(() -> escrowService.fund(taskId, publisherId,
                new BigDecimal("5001.00"), "CH-over-" + RANDOM.nextInt(1_000_000), "WECHAT"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.AMOUNT_MISMATCH.getCode());

        // 两次失败都不该留下任何订单
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_order WHERE task_id = ?", Integer.class, taskId)).isZero();
    }

    @Test
    @DisplayName("⭐ 幂等：同一渠道单号重复回调只记一次（渠道超时重推是常态）")
    void same_channel_order_no_is_idempotent() {
        Long taskId = seedPendingEscrow("BID", "BID");
        String channelNo = "CH-dup-" + RANDOM.nextInt(1_000_000);

        EscrowResult first = escrowService.fund(taskId, publisherId,
                new BigDecimal("5000.00"), channelNo, "WECHAT");
        EscrowResult second = escrowService.fund(taskId, publisherId,
                new BigDecimal("5000.00"), channelNo, "WECHAT");

        assertThat(second.isIdempotent()).isTrue();
        assertThat(second.getEscrowId()).isEqualTo(first.getEscrowId());
        assertThat(second.getOrderId()).isEqualTo(first.getOrderId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM escrow WHERE channel_order_no = ?", Integer.class, channelNo))
                .as("渠道重复回调不能记成两笔托管")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_order WHERE task_id = ?", Integer.class, taskId)).isEqualTo(1);
    }

    @Test
    @DisplayName("只有发单方能托管自己的任务；状态不对也不能托管")
    void only_publisher_and_only_pending_escrow() {
        Long taskId = seedPendingEscrow("BID", "BID");

        assertThatThrownBy(() -> escrowService.fund(taskId, publisherId + 99999,
                new BigDecimal("5000.00"), "CH-x-" + RANDOM.nextInt(1_000_000), "WECHAT"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_TASK_OWNER.getCode());

        // 已招募中的任务不能再托管第二次
        jdbc.update("UPDATE task SET status = 'RECRUITING' WHERE id = ?", taskId);
        assertThatThrownBy(() -> escrowService.fund(taskId, publisherId,
                new BigDecimal("5000.00"), "CH-y-" + RANDOM.nextInt(1_000_000), "WECHAT"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TASK_STATUS_INVALID.getCode());
    }

    @Test
    @DisplayName("托管超时未支付 → 已取消（发单方放着不管不能一直占着任务）")
    void escrow_timeout_cancels_task() {
        Long taskId = seedPendingEscrow("BID", "BID");

        escrowService.cancelOnTimeout(taskId, "托管超时未支付（30 分钟）");

        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId))
                .isEqualTo(TaskStatus.CANCELLED.name());
    }
}
