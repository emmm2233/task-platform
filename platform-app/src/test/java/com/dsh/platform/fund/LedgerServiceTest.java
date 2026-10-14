package com.dsh.platform.fund;

import com.dsh.platform.common.BizException;
import com.dsh.platform.fund.service.LedgerService;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.SmsCodeService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账本（{@code wallet_account} + {@code wallet_txn}）—— T17 分账的前置。
 *
 * <p>⭐ <b>为什么资金模块必须先有账本</b>：{@code escrow} 表只说明"这笔钱托管了"，
 * 它回答不了三个问题：
 * <ol>
 *   <li>平台账上<b>现在</b>有多少托管中的钱？（对账要跟渠道流水比这个数）</li>
 *   <li>这笔托管对应的科目在哪？（托管池 / 佣金 / 用户余额是不同科目）</li>
 *   <li>钱是怎么动的？（复式记账：从哪个科目到哪个科目）</li>
 * </ol>
 * 没有账本就分账，等于对着 {@code escrow} 表做加减法 —— 一旦出现差额，
 * 无从判断是漏记、重复还是记错。
 *
 * <p>三条规则：
 * <ol>
 *   <li>⭐ <b>托管池是平台科目</b>（{@code user_id} 为 NULL，{@code account_type=ESCROW_HOLD}）
 *       —— 不是某个用户的账户。它存在<b>恰好一个</b>；</li>
 *   <li>⭐ <b>托管中的钱记在 {@code frozen} 而不是 {@code available}</b> ——
 *       未验收前这笔钱不能动；{@code ck_wallet_non_negative} 从库层保证两个余额都不为负；</li>
 *   <li>⭐ <b>对手方在账本之外</b>：发单方通过渠道直接付款，钱没进过他的可用余额，
 *       所以分录是 {@code from_account_id = NULL}（渠道）→ 托管池，
 *       而不是从他的 {@code USER.available} 扣。</li>
 * </ol>
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerServiceTest {

    private static final Random RANDOM = new Random();

    @Autowired private LedgerService ledgerService;
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
        // 清掉历史遗留的托管池余额，避免跨用例相互影响
        jdbc.update("DELETE FROM wallet_txn WHERE biz_type = 'ESCROW_FREEZE'");
        jdbc.update("UPDATE wallet_account SET frozen = 0, available = 0 WHERE account_type = 'ESCROW_HOLD'");
    }

    private Long escrowHoldAccountId() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM wallet_account WHERE account_type = 'ESCROW_HOLD' AND user_id IS NULL", Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private BigDecimal frozenOfHold() {
        Long id = escrowHoldAccountId();
        if (id == null) {
            return BigDecimal.ZERO;
        }
        return jdbc.queryForObject("SELECT frozen FROM wallet_account WHERE id = ?", BigDecimal.class, id);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 托管冻结：托管池 frozen 增加，并写一条【方向正确】的复式分录")
    void freeze_escrow_increases_hold_frozen_and_writes_entry() {
        BigDecimal before = frozenOfHold();
        BigDecimal amount = new BigDecimal("5000.00");
        String key = "ESC-FREEZE-" + RANDOM.nextInt(1_000_000);

        Long accountId = ledgerService.freezeEscrow(1L, 1L, amount, key);

        assertThat(frozenOfHold())
                .as("托管中的钱记在 frozen，不是 available")
                .isEqualByComparingTo(before.add(amount));

        var txn = jdbc.queryForMap("SELECT * FROM wallet_txn WHERE idempotent_key = ?", key);
        assertThat(txn.get("biz_type")).isEqualTo("ESCROW_FREEZE");
        // ck_txn_direction 只接受 DEBIT / CREDIT（不是 IN / OUT）—— 钱流入托管池即 CREDIT
        assertThat(txn.get("direction")).isEqualTo("CREDIT");
        // queryForMap 返回 Object，必须先转 BigDecimal —— ObjectAssert 没有 isEqualByComparingTo
        assertThat((BigDecimal) txn.get("amount")).isEqualByComparingTo(amount);
        // ⭐ 对手方是渠道（在账本之外），所以 from_account_id 为空、to 是托管池
        assertThat(txn.get("from_account_id")).as("发单方直接走渠道付款，钱没进过他的可用余额").isNull();
        assertThat(((Number) txn.get("to_account_id")).longValue()).isEqualTo(accountId);

        // 托管池此时不该有可用余额（钱是冻结的）
        assertThat(jdbc.queryForObject(
                "SELECT available FROM wallet_account WHERE id = ?", BigDecimal.class, accountId))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("⭐ 托管池【恰好一个】：多次冻结不重复建平台科目账户")
    void escrow_hold_account_is_a_singleton() {
        ledgerService.freezeEscrow(2L, 2L, new BigDecimal("100.00"), "K-a-" + RANDOM.nextInt(1_000_000));
        ledgerService.freezeEscrow(3L, 3L, new BigDecimal("200.00"), "K-b-" + RANDOM.nextInt(1_000_000));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_account WHERE account_type = 'ESCROW_HOLD'", Integer.class))
                .as("托管池是平台科目，全局只有一个；建成多个会让对账永远对不上")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 幂等：同 idempotent_key 重复调用只记一次（uk_idem 兜底渠道重推）")
    void freeze_is_idempotent_by_key() {
        String key = "K-dup-" + RANDOM.nextInt(1_000_000);
        BigDecimal before = frozenOfHold();

        ledgerService.freezeEscrow(4L, 4L, new BigDecimal("300.00"), key);
        BigDecimal afterFirst = frozenOfHold();
        ledgerService.freezeEscrow(4L, 4L, new BigDecimal("300.00"), key);

        assertThat(frozenOfHold())
                .as("重复回调不能把托管池加两次")
                .isEqualByComparingTo(afterFirst);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_txn WHERE idempotent_key = ?", Integer.class, key))
                .isEqualTo(1);
        assertThat(afterFirst).isEqualByComparingTo(before.add(new BigDecimal("300.00")));
    }

    @Test
    @DisplayName("金额必须为正；为负会试图把托管池减成负数（库层 CHECK 也会拦）")
    void non_positive_amount_is_rejected() {
        assertThatThrownBy(() -> ledgerService.freezeEscrow(
                5L, 5L, new BigDecimal("0.00"), "K-zero-" + RANDOM.nextInt(1_000_000)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ledgerService.freezeEscrow(
                5L, 5L, new BigDecimal("-100.00"), "K-neg-" + RANDOM.nextInt(1_000_000)))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("分录号 txn_no 唯一且非空（对账要按它跟渠道流水逐笔勾对）")
    void txn_no_is_present_and_unique() {
        String k1 = "K-n1-" + RANDOM.nextInt(1_000_000);
        String k2 = "K-n2-" + RANDOM.nextInt(1_000_000);
        ledgerService.freezeEscrow(6L, 6L, new BigDecimal("10.00"), k1);
        ledgerService.freezeEscrow(7L, 7L, new BigDecimal("10.00"), k2);

        List<String> nos = jdbc.queryForList(
                "SELECT txn_no FROM wallet_txn WHERE idempotent_key IN (?, ?)", String.class, k1, k2);
        assertThat(nos).hasSize(2).doesNotContainNull();
        assertThat(nos.get(0)).isNotEqualTo(nos.get(1));
    }

    @Test
    @DisplayName("⭐⭐ 并发入账不丢更新：N 笔并发冻结后，托管池 frozen 必须恰好等于 N × 金额")
    void concurrent_freezes_do_not_lose_updates() throws Exception {
        // ⭐ 本用例补的是 fund 模块【唯一缺失的一类覆盖】：并发。
        //    修复前 freezeEscrow 是"先 SELECT frozen → 在 Java 里加 → SET frozen = <绝对值>"，
        //    version 虽然自增却从不参与 WHERE ⇒ 两个事务读到同一个旧值就后写覆盖前写。
        //    触发场景很常见：两个发单方同时付款，或同一人同时付两单。
        //    ⛔ 它不会报错、不会让余额变负（ck_wallet_non_negative 抓不到），
        //       只会让账本少记 —— 直到对账项「Σ frozen == Σ escrow 未释放」不平才暴露。
        //
        // ⚠️ 这是"概率型"探测：线程够多、且有起跑线（latch）让窗口重叠，
        //    旧写法几乎必然至少丢一次；但它不是形式化证明（那种要靠条件更新的写法本身保证）。
        final int threads = 12;
        final int perThread = 5;
        final BigDecimal amount = new BigDecimal("7.77");
        final BigDecimal base = frozenOfHold();
        final long runId = RANDOM.nextInt(1_000_000);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ledgerService.freezeEscrow(
                                10_000L + runId, 10_000L + runId, amount,
                                "K-conc-" + runId + "-" + tid + "-" + i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        BigDecimal expected = base.add(amount.multiply(BigDecimal.valueOf((long) threads * perThread)));
        assertThat(frozenOfHold())
                .as("并发入账丢更新：%d 线程 × %d 笔 × %s 必须全部记上", threads, perThread, amount)
                .isEqualByComparingTo(expected);
    }
}
