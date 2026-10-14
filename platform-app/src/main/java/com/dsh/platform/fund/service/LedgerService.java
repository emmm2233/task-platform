package com.dsh.platform.fund.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.common.db.RowValues;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 平台账本（{@code wallet_account} + {@code wallet_txn}）。
 *
 * <p>⭐ {@code escrow} 表只说明"这笔钱托管了"，账本才回答"平台账上现在有多少托管中的钱、
 * 它在哪个科目、是怎么动的"。没有账本就分账，等于对着 {@code escrow} 表做加减法 ——
 * 一旦出现差额，无从判断是漏记、重复还是记错。
 *
 * <h2>科目模型</h2>
 * {@code wallet_account.account_type} 四类：
 * {@code USER}（用户）· {@code PLATFORM_COMMISSION}（平台佣金）·
 * {@code PLATFORM_DEPOSIT}（保证金）· {@code ESCROW_HOLD}（托管池）。
 * 平台科目账户的 {@code user_id} 为 NULL。
 *
 * <h2>两条不变量</h2>
 * <ol>
 *   <li>⭐ <b>托管池全局恰好一个</b> —— 它是平台科目，不是某个用户的账户。
 *       建成多个会让对账永远对不上（{@code uk_user_type} 因 user_id 为 NULL
 *       在 MySQL 里<b>不会</b>阻止重复插入，所以这里必须靠"先查后插"保证）；</li>
 *   <li>⭐ <b>托管中的钱记 {@code frozen} 而非 {@code available}</b> ——
 *       未验收前这笔钱不能动。{@code ck_wallet_non_negative} 从库层保证两个余额非负。</li>
 * </ol>
 *
 * <h2>对手方在账本之外</h2>
 * 发单方通过渠道<b>直接付款</b>，钱没进过他的可用余额 ——
 * 所以分录是 {@code from_account_id = NULL}（渠道）→ 托管池，
 * ⛔ 不是从他的 {@code USER.available} 扣。若写错成后者，
 * 用户余额会凭空变成负数并被 CHECK 拦下（这是好事：错误会立刻暴露而不是静默错账）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    /** 业务类型：托管冻结。 */
    public static final String BIZ_ESCROW_FREEZE = "ESCROW_FREEZE";

    /** 业务类型：份额释放（托管池 → 接单方）。 */
    public static final String BIZ_SHARE_RELEASE = "SHARE_RELEASE";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 托管冻结分录：渠道 → 托管池（frozen 增加）。
     *
     * @param idempotentKey 幂等键（{@code uk_idem} 兜底渠道重推）
     * @return 托管池账户 id
     */
    @Transactional
    public Long freezeEscrow(Long orderId, Long escrowId, BigDecimal amount, String idempotentKey) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "冻结金额必须大于 0");
        }
        if (idempotentKey == null || idempotentKey.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "幂等键不能为空");
        }

        Long holdId = escrowHoldAccountId();

        // 幂等：同键只记一次。放在建账户之后，这样重复调用也不会多建账户。
        List<Map<String, Object>> existed = jdbc.queryForList(
                "SELECT id FROM wallet_txn WHERE idempotent_key = ?", idempotentKey);
        if (!existed.isEmpty()) {
            log.info("账本幂等命中 idempotentKey={}", idempotentKey);
            return holdId;
        }

        // ⭐ 冻结：加 frozen 而不是 available。
        //
        // ⛔⛔ 必须是【原子自增 + 影响行数判定】，不能写成"先 SELECT 出来、在 Java 里加好、
        //     再 SET frozen = <绝对值>"——那个写法在并发下必然丢更新：
        //       T1 读 frozen=1000，T2 也读 frozen=1000（T1 还没提交），
        //       两笔各 500 ⇒ T1 写 1100、T2 阻塞后也写 1100，真实应记 2000，账本只记 1100。
        //     而 wallet_account.version 虽然自增，却【从不参与 WHERE】，等于没有乐观锁；
        //     ck_wallet_non_negative 也抓不到（余额仍然 ≥0），
        //     最终对账项「Σ wallet_account.frozen == Σ escrow 未释放金额」必然不平。
        //     ⭐ 这与 application-dev.yml 写明的口径一致：
        //        "先检查后更新"的竞态由【条件 UPDATE】解决（TaskGrabMapper.grabAtomic 就是这么做的）。
        int updated = jdbc.update("""
                UPDATE wallet_account
                   SET frozen = frozen + ?, total_in = total_in + ?, version = version + 1, updated_at = NOW(3)
                 WHERE id = ?
                """, amount, amount, holdId);
        if (updated != 1) {
            throw new BizException(ErrorCode.SYSTEM_BUSY, "托管池入账失败：账户不存在 id=" + holdId);
        }

        // 回读作为 balance_after：同一事务内看得到自己刚写的值，
        // 且该行的写锁持有到提交，不会被并发插队改掉。
        BigDecimal after = RowValues.nzDecimal(jdbc.queryForObject(
                "SELECT frozen FROM wallet_account WHERE id = ?", BigDecimal.class, holdId));

        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> cols = RowValues.of(
                "txn_no", "T" + Long.toString(System.nanoTime(), 36),
                "biz_type", BIZ_ESCROW_FREEZE,
                // ck_txn_direction 只接受 DEBIT / CREDIT；钱流入托管池即 CREDIT
                "direction", "CREDIT",
                // 对手方（渠道）在账本之外 —— 不是从某个用户余额扣
                "from_account_id", null,
                "to_account_id", holdId,
                "amount", amount,
                "order_id", orderId,
                "escrow_id", escrowId,
                "balance_after", after,
                "idempotent_key", idempotentKey,
                "status", "SUCCESS",
                "remark", "托管冻结",
                "created_at", now);
        String[] p = RowValues.insertParts(cols);
        jdbc.update("INSERT INTO wallet_txn (" + p[0] + ") VALUES (" + p[1] + ")",
                cols.values().toArray());

        log.info("托管冻结入账 托管池={} 金额={} 冻结后={}", holdId, amount.toPlainString(), after.toPlainString());
        return holdId;
    }

    /**
     * 份额释放：托管池 → 接单方。
     *
     * <p>⭐ T17 会按 {@code escrow_share} 逐份额调用本方法，所以三件事由本方法自己保证：
     * <ol>
     *   <li>⭐ <b>释放不得超过冻结额</b> —— 托管池里是<b>别人的钱</b>，
     *       多放一分就是平台垫付，账上会凭空多出钱。这里做<b>显式预检</b>并给业务错误码；
     *       {@code ck_wallet_non_negative} 是最后一道兜底，但让它抛 SQL 异常
     *       对调用方毫无指导意义；</li>
     *   <li>⭐ <b>幂等</b> —— 分账超时重试是常态，重复释放等于重复付钱；</li>
     *   <li><b>双余额语义</b> —— 托管池减 {@code frozen}（这笔钱本就是冻结的），
     *       接单方加 {@code available}（钱已可提现，不该再冻着）。</li>
     * </ol>
     *
     * <p>{@code direction} 以 {@code to_account_id} 为视角：{@code CREDIT} = 使收款账户增加。
     * 冻结与释放都是 CREDIT（收款方分别是托管池与接单方），区别在 {@code biz_type}。
     */
    @Transactional
    public void releaseShare(Long orderId, Long escrowId, Long subOrderId, Long takerId,
                             BigDecimal amount, String idempotentKey) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "释放金额必须大于 0");
        }
        if (idempotentKey == null || idempotentKey.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "幂等键不能为空");
        }

        Long holdId = escrowHoldAccountId();
        if (isDuplicated(idempotentKey)) {
            log.info("释放幂等命中 idempotentKey={}", idempotentKey);
            return;
        }

        Long takerAccountId = userAccountId(takerId);

        // ⭐ 把"释放不得超过冻结额"从【预检】改成【条件更新的 WHERE 谓词】+ 影响行数判定。
        //    ⛔ 原写法先 SELECT frozen 预检、再 SET frozen = <算好的绝对值>：
        //       ① 两个并发释放读到同一个旧值 ⇒ 后写覆盖前写（丢更新，账本少记）；
        //       ② 反向交错（入账与释放并发）会让余额虚高，预检被这虚高值放行
        //          ⇒ 平台替托管池垫付。
        //    两处都只有把判据下沉到 UPDATE 的 WHERE 里才真正成立。
        int released = jdbc.update("""
                UPDATE wallet_account
                   SET frozen = frozen - ?, total_out = total_out + ?, version = version + 1, updated_at = NOW(3)
                 WHERE id = ? AND frozen >= ?
                """, amount, amount, holdId, amount);
        if (released != 1) {
            // 区分"余额不足"与"账户不存在"没有意义 —— 后者不该发生；给出当前余额便于定位。
            BigDecimal current = RowValues.nzDecimal(jdbc.queryForObject(
                    "SELECT frozen FROM wallet_account WHERE id = ?", BigDecimal.class, holdId));
            throw new BizException(ErrorCode.AMOUNT_MISMATCH,
                    "释放金额（" + amount.toPlainString() + "）超过托管池冻结余额（"
                            + current.toPlainString() + "）");
        }

        // 接单方：加 available（不是 frozen），并记累计收入
        int credited = jdbc.update("""
                UPDATE wallet_account
                   SET available = available + ?, total_in = total_in + ?, version = version + 1, updated_at = NOW(3)
                 WHERE id = ?
                """, amount, amount, takerAccountId);
        if (credited != 1) {
            throw new BizException(ErrorCode.SYSTEM_BUSY, "接单方钱包账户不存在 id=" + takerAccountId);
        }

        BigDecimal takerAfter = RowValues.nzDecimal(jdbc.queryForObject(
                "SELECT available FROM wallet_account WHERE id = ?", BigDecimal.class, takerAccountId));
        // 托管池剩余冻结：同样回读（用于日志与排查；不是判据 —— 判据在 UPDATE 的 WHERE 里）
        BigDecimal holdAfter = RowValues.nzDecimal(jdbc.queryForObject(
                "SELECT frozen FROM wallet_account WHERE id = ?", BigDecimal.class, holdId));

        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> cols = RowValues.of(
                "txn_no", "T" + Long.toString(System.nanoTime(), 36),
                "biz_type", BIZ_SHARE_RELEASE,
                "direction", "CREDIT",
                "from_account_id", holdId,
                "to_account_id", takerAccountId,
                "amount", amount,
                "order_id", orderId,
                "sub_order_id", subOrderId,
                "escrow_id", escrowId,
                "balance_after", takerAfter,
                "idempotent_key", idempotentKey,
                "status", "SUCCESS",
                "remark", "份额释放",
                "created_at", now);
        String[] p = RowValues.insertParts(cols);
        jdbc.update("INSERT INTO wallet_txn (" + p[0] + ") VALUES (" + p[1] + ")",
                cols.values().toArray());

        log.info("份额释放 托管池={} → 接单方账户={} 金额={} 托管池剩余冻结={}",
                holdId, takerAccountId, amount.toPlainString(), holdAfter.toPlainString());
    }

    /** 幂等键是否已入账。 */
    private boolean isDuplicated(String idempotentKey) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_txn WHERE idempotent_key = ?", Integer.class, idempotentKey);
        return n != null && n > 0;
    }

    /**
     * 取（必要时创建）用户的 {@code USER} 账户。
     *
     * <p>⚠️ 不能假设"用户一定有账户"：接单方可能从未充值或提现过，
     * 他的账户是在<b>第一次收到钱</b>时才建的。⛔ 若这里直接取不到就报错，
     * 表现会是"分账失败"，而根因只是"他还没建过账户"——排查成本很高。
     */
    private Long userAccountId(Long userId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM wallet_account WHERE user_id = ? AND account_type = 'USER' LIMIT 1",
                Long.class, userId);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> cols = RowValues.of(
                "user_id", userId,
                "account_type", "USER",
                "account_no", "U" + Long.toString(System.nanoTime(), 36),
                "available", BigDecimal.ZERO,
                "frozen", BigDecimal.ZERO,
                "total_in", BigDecimal.ZERO,
                "total_out", BigDecimal.ZERO,
                "version", 0,
                "created_at", now,
                "updated_at", now);
        String[] p = RowValues.insertParts(cols);
        jdbc.update("INSERT INTO wallet_account (" + p[0] + ") VALUES (" + p[1] + ")",
                cols.values().toArray());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        log.info("创建用户钱包账户 userId={} accountId={}", userId, id);
        return id;
    }

    /**
     * 取（必要时创建）托管池账户。
     *
     * <p>⚠️ 不能靠 {@code uk_user_type} 防重复：该唯一键含 {@code user_id}，
     * 而平台科目的 {@code user_id} 为 NULL —— MySQL 的唯一索引<b>允许多个 NULL</b>，
     * 所以重复插入不会被拦。必须"先查后插"，且在并发下由外层事务与
     * 调用方的串行性保证（托管是单任务串行流程，不会并发建池）。
     */
    private Long escrowHoldAccountId() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM wallet_account WHERE account_type = 'ESCROW_HOLD' AND user_id IS NULL LIMIT 1",
                Long.class);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> cols = RowValues.of(
                "user_id", null,
                "account_type", "ESCROW_HOLD",
                "account_no", "HOLD" + Long.toString(System.nanoTime(), 36),
                "available", BigDecimal.ZERO,
                "frozen", BigDecimal.ZERO,
                "total_in", BigDecimal.ZERO,
                "total_out", BigDecimal.ZERO,
                "version", 0,
                "created_at", now,
                "updated_at", now);
        String[] p = RowValues.insertParts(cols);
        jdbc.update("INSERT INTO wallet_account (" + p[0] + ") VALUES (" + p[1] + ")",
                cols.values().toArray());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        log.info("创建平台托管池账户 id={}", id);
        return id;
    }
}