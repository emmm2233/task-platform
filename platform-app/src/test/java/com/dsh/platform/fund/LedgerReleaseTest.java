package com.dsh.platform.fund;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.fund.service.LedgerService;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.SmsCodeService;
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
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账本释放腿（托管池 → 接单方）。
 *
 * <p>⭐ 这是分账的核心原语。T17 会按 {@code escrow_share} 逐份额调用它，
 * 所以它必须自己保证三件事，而不是指望调用方：
 * <ol>
 *   <li>⭐ <b>释放不得超过冻结额</b> —— 托管池的钱是别人的钱，
 *       多放一分就是平台垫付。这条不变量一旦破，账上会出现"凭空多出来的钱"；</li>
 *   <li>⭐ <b>幂等</b> —— 分账可能因超时重试，重复释放等于重复付钱；</li>
 *   <li><b>双余额语义正确</b> —— 托管池减 {@code frozen}（不是 available），
 *       接单方加 {@code available}（不是 frozen，钱已经可提现了）。</li>
 * </ol>
 *
 * <p>{@code direction} 的口径：<b>以 {@code to_account_id} 为视角</b> ——
 * {@code CREDIT} 表示这笔使收款账户增加。冻结与释放都是 CREDIT（收款方分别是托管池与接单方），
 * 区别在 {@code biz_type}。⛔ 不要用 DEBIT/CREDIT 表示"转出/转入"，
 * 那会让同一笔分录在两端看起来是相反的方向。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerReleaseTest {

    private static final Random RANDOM = new Random();

    @Autowired private LedgerService ledgerService;
    @Autowired private AuthService authService;
    @Autowired private SmsCodeService smsCodeService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private Long takerId;

    @BeforeAll
    void setUp() {
        var keys = redis.keys("sms:limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        String phone = "138" + String.format("%08d", RANDOM.nextInt(100_000_000));
        String ip = "10." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(254);
        String code = smsCodeService.send(phone, SmsScene.REGISTER, ip, "dev-" + RANDOM.nextLong());
        takerId = authService.register(phone, "Passw0rd!", "接单方", code).getUserId();

        jdbc.update("DELETE FROM wallet_txn WHERE biz_type IN ('ESCROW_FREEZE','SHARE_RELEASE')");
        jdbc.update("UPDATE wallet_account SET frozen = 0, available = 0, total_in = 0, total_out = 0 "
                + "WHERE account_type = 'ESCROW_HOLD'");
    }

    /**
     * ⭐ 每个用例都从【固定起点】开始。
     *
     * <p>踩过的坑：seedFrozen 是<b>累加</b>到共享托管池的，
     * 于是「释放 1000.01 应被拒」的用例在一个已被前面用例加大的池子上
     * 反而不超额 —— 用例之间通过共享状态互相污染。
     * 这与 T7 哈希链「每个用例独占一个小时段」是同一类问题。
     */
    @BeforeEach
    void resetBalances() {
        jdbc.update("DELETE FROM wallet_txn WHERE biz_type IN ('ESCROW_FREEZE','SHARE_RELEASE')");
        jdbc.update("UPDATE wallet_account SET frozen = 0, available = 0, total_in = 0, total_out = 0 "
                + "WHERE account_type = 'ESCROW_HOLD' OR user_id IS NOT NULL");
    }

    private Long holdId() {
        return jdbc.queryForObject(
                "SELECT id FROM wallet_account WHERE account_type = 'ESCROW_HOLD' AND user_id IS NULL",
                Long.class);
    }

    private BigDecimal frozenOfHold() {
        return jdbc.queryForObject(
                "SELECT frozen FROM wallet_account WHERE id = ?", BigDecimal.class, holdId());
    }

    private Long userAccountId(Long userId) {
        var ids = jdbc.queryForList(
                "SELECT id FROM wallet_account WHERE user_id = ? AND account_type = 'USER'", Long.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private BigDecimal availableOfUser(Long userId) {
        Long id = userAccountId(userId);
        if (id == null) {
            return BigDecimal.ZERO;
        }
        return jdbc.queryForObject(
                "SELECT available FROM wallet_account WHERE id = ?", BigDecimal.class, id);
    }

    /** 先冻结一笔钱，作为释放的来源。 */
    private Long seedFrozen(String amount) {
        String k = "SEED-" + RANDOM.nextInt(1_000_000);
        ledgerService.freezeEscrow(1L, 1L, new BigDecimal(amount), k);
        return jdbc.queryForObject(
                "SELECT id FROM wallet_txn WHERE idempotent_key = ?", Long.class, k);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 释放：托管池减 frozen（不是 available），接单方加 available（不是 frozen）")
    void release_moves_money_from_hold_to_taker() {
        seedFrozen("5000.00");
        BigDecimal holdBefore = frozenOfHold();
        BigDecimal userBefore = availableOfUser(takerId);
        String key = "REL-" + RANDOM.nextInt(1_000_000);

        ledgerService.releaseShare(1L, 1L, 10L, takerId, new BigDecimal("3000.00"), key);

        assertThat(frozenOfHold())
                .as("托管池减的是 frozen —— 这笔钱本来就是冻结的")
                .isEqualByComparingTo(holdBefore.subtract(new BigDecimal("3000.00")));
        assertThat(availableOfUser(takerId))
                .as("接单方加的是 available —— 钱已可提现，不该再冻着")
                .isEqualByComparingTo(userBefore.add(new BigDecimal("3000.00")));

        var txn = jdbc.queryForMap("SELECT * FROM wallet_txn WHERE idempotent_key = ?", key);
        assertThat(txn.get("biz_type")).isEqualTo("SHARE_RELEASE");
        // direction 以 to_account 为视角：这笔使接单方账户增加
        assertThat(txn.get("direction")).isEqualTo("CREDIT");
        assertThat(((Number) txn.get("from_account_id")).longValue()).isEqualTo(holdId());
        assertThat(((Number) txn.get("to_account_id")).longValue()).isEqualTo(userAccountId(takerId));
        assertThat((BigDecimal) txn.get("amount")).isEqualByComparingTo("3000.00");
    }

    @Test
    @DisplayName("⭐ 释放不得超过冻结额 —— 托管池的钱是别人的，多放一分就是平台垫付")
    void release_cannot_exceed_frozen() {
        seedFrozen("1000.00");
        BigDecimal holdBefore = frozenOfHold();

        assertThatThrownBy(() -> ledgerService.releaseShare(
                1L, 1L, 11L, takerId, new BigDecimal("1000.01"), "REL-over-" + RANDOM.nextInt(1_000_000)))
                .as("超出 0.01 也必须拦 —— 资金安全没有「差不多」")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.AMOUNT_MISMATCH.getCode());

        assertThat(frozenOfHold())
                .as("被拒的释放不能动到托管池")
                .isEqualByComparingTo(holdBefore);
    }

    @Test
    @DisplayName("⭐ 幂等：同 key 重复释放只付一次（分账超时重试不能重复付钱）")
    void release_is_idempotent() {
        seedFrozen("2000.00");
        String key = "REL-dup-" + RANDOM.nextInt(1_000_000);
        BigDecimal userBefore = availableOfUser(takerId);

        ledgerService.releaseShare(2L, 2L, 20L, takerId, new BigDecimal("800.00"), key);
        BigDecimal afterFirst = availableOfUser(takerId);
        ledgerService.releaseShare(2L, 2L, 20L, takerId, new BigDecimal("800.00"), key);

        assertThat(availableOfUser(takerId))
                .as("重复释放不能把钱付两次")
                .isEqualByComparingTo(afterFirst);
        assertThat(afterFirst).isEqualByComparingTo(userBefore.add(new BigDecimal("800.00")));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_txn WHERE idempotent_key = ?", Integer.class, key)).isEqualTo(1);
    }

    @Test
    @DisplayName("接单方账户不存在时自动创建（不能因为「还没建过账户」就分不了账）")
    void taker_account_is_created_on_demand() {
        seedFrozen("500.00");
        // 换一个从没建过钱包的用户
        // ⚠️ 手机号必须【同一个】—— 踩过：send 与 register 各写了一次 RANDOM.nextInt，
        //    验证码发给了 A、注册用的是 B，必然失败
        String phone = "139" + String.format("%08d", RANDOM.nextInt(100_000_000));
        String code = smsCodeService.send(phone, SmsScene.REGISTER, "10.1.1.1", "dev-" + RANDOM.nextLong());
        Long fresh = authService.register(phone, "Passw0rd!", "新接单方", code).getUserId();

        ledgerService.releaseShare(3L, 3L, 30L, fresh, new BigDecimal("500.00"),
                "REL-new-" + RANDOM.nextInt(1_000_000));

        assertThat(userAccountId(fresh)).as("应自动建 USER 账户").isNotNull();
        assertThat(availableOfUser(fresh)).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("金额必须为正")
    void non_positive_amount_is_rejected() {
        seedFrozen("100.00");
        assertThatThrownBy(() -> ledgerService.releaseShare(
                4L, 4L, 40L, takerId, BigDecimal.ZERO, "REL-z-" + RANDOM.nextInt(1_000_000)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ledgerService.releaseShare(
                4L, 4L, 40L, takerId, new BigDecimal("-1.00"), "REL-n-" + RANDOM.nextInt(1_000_000)))
                .isInstanceOf(BizException.class);
    }
}
