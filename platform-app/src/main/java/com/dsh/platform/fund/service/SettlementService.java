package com.dsh.platform.fund.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.fund.dto.SettleResult;
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
 * 分账编排：按 {@code escrow_share} 逐份额把托管池的钱放给接单方。
 *
 * <p>⭐ <b>分账要解决的核心问题是"钱按什么依据分"</b>。
 * 依据是 {@code escrow_share} —— 每个结算单元一条份额记录。
 * 本类只做编排，实际转账由 {@link LedgerService#releaseShare} 完成
 * （它自己保证不超放与幂等，见其注释）。
 *
 * <h2>四条不变量</h2>
 * <ol>
 *   <li>⭐⭐ <b>配平：{@code Σ share_amount == escrow.amount}</b>。
 *       少了 → 有份额永远释放不了，<b>钱卡在托管池（平台占着别人的钱）</b>；
 *       多了 → 超发。必须在<b>分账开始前整体校验</b>，而不是边分边发现 ——
 *       边分边发现意味着已经放了一部分出去，回滚要靠事务，而
 *       如果分账是分批执行的（份额多、跨批次），已经放出去的退款极其麻烦；</li>
 *   <li>⭐ <b>可重入</b>：已 RELEASED 的份额跳过；且 {@code releaseShare}
 *       的幂等键 {@code SHARE:<shareId>} 是第二道保险；</li>
 *   <li>⭐ <b>状态如实反映进度</b>：全部释放 → {@code RELEASED}；
 *       部分 → {@code PARTIAL_RELEASED}。⛔ 不能一律写 RELEASED，
 *       否则对账时看不出还有钱没放出去；</li>
 *   <li>⭐ <b>争议份额不参与</b>：{@code DISPUTE_FROZEN} 的钱必须留在托管池 ——
 *       争议未裁决就放款，等于把仲裁结果提前执行了。</li>
 * </ol>
 *
 * <p>⚠️ 库层兜底：{@code ck_share_sum} 保证
 * {@code released + refunded + frozen <= share_amount}，单份额超放会被数据库拦下。
 * 但那是最后一道 —— 配平要在业务层先校验才能给出可读原因。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    /** 可释放的份额状态。{@code DISPUTE_FROZEN} 刻意不在其中。 */
    private static final List<String> RELEASABLE = List.of("PENDING");

    private final LedgerService ledgerService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 全量分账。
     *
     * @return 本次释放的份额数与金额
     */
    @Transactional
    public SettleResult settle(Long orderId, Long escrowId) {
        // ① 锁托管单，避免并发分账
        List<Map<String, Object>> esc = jdbc.queryForList(
                "SELECT amount, released_amount, status FROM escrow WHERE id = ? FOR UPDATE", escrowId);
        if (esc.isEmpty()) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND.getCode() == 0
                    ? ErrorCode.PARAM_INVALID : ErrorCode.PARAM_INVALID, "托管单不存在：" + escrowId);
        }
        BigDecimal escrowAmount = (BigDecimal) esc.get(0).get("amount");

        // ② ⭐ 配平校验：分账【开始前】整体校验，不边分边发现
        List<Map<String, Object>> shares = jdbc.queryForList(
                "SELECT id, share_amount, status FROM escrow_share "
                        + "WHERE escrow_id = ? AND status <> 'CANCELLED' ORDER BY id", escrowId);
        BigDecimal allocated = shares.stream()
                .map(s -> (BigDecimal) s.get("share_amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocated.compareTo(escrowAmount) != 0) {
            throw new BizException(ErrorCode.AMOUNT_MISMATCH,
                    "份额合计（" + allocated.toPlainString() + "）与托管额（"
                            + escrowAmount.toPlainString() + "）不配平");
        }

        // ③ 逐份额释放
        int released = 0;
        BigDecimal releasedAmount = BigDecimal.ZERO;
        for (Map<String, Object> s : shares) {
            if (!RELEASABLE.contains(String.valueOf(s.get("status")))) {
                continue;   // 已释放 / 争议冻结 / 已退款 —— 一律跳过
            }
            BigDecimal amt = (BigDecimal) s.get("share_amount");
            doReleaseShare(orderId, escrowId, ((Number) s.get("id")).longValue(), amt);
            released++;
            releasedAmount = releasedAmount.add(amt);
        }

        String status = refreshEscrowStatus(escrowId, escrowAmount);
        log.info("分账完成 escrowId={} 本次释放 {} 条 / {} 元，托管单状态={}",
                escrowId, released, releasedAmount.toPlainString(), status);
        return SettleResult.builder().releasedCount(released)
                .releasedAmount(releasedAmount).status(status).build();
    }

    /** 单份额释放（用于争议和解后单独放某一条）。 */
    @Transactional
    public SettleResult settleShare(Long escrowId, Long shareId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, order_id, taker_id, share_amount, status FROM escrow_share "
                        + "WHERE id = ? AND escrow_id = ? FOR UPDATE", shareId, escrowId);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "份额不存在：" + shareId);
        }
        Map<String, Object> s = rows.get(0);
        BigDecimal amt = (BigDecimal) s.get("share_amount");

        int released = 0;
        BigDecimal releasedAmount = BigDecimal.ZERO;
        if (RELEASABLE.contains(String.valueOf(s.get("status")))) {
            doReleaseShare(((Number) s.get("order_id")).longValue(), escrowId, shareId, amt);
            released = 1;
            releasedAmount = amt;
        }
        BigDecimal escrowAmount = jdbc.queryForObject(
                "SELECT amount FROM escrow WHERE id = ?", BigDecimal.class, escrowId);
        String status = refreshEscrowStatus(escrowId, escrowAmount);
        return SettleResult.builder().releasedCount(released)
                .releasedAmount(releasedAmount).status(status).build();
    }

    // ------------------------------------------------------------------

    /** 释放一条份额：账本转账 + 份额置 RELEASED。 */
    private void doReleaseShare(Long orderId, Long escrowId, Long shareId, BigDecimal amount) {
        Long takerId = jdbc.queryForObject(
                "SELECT taker_id FROM escrow_share WHERE id = ?", Long.class, shareId);

        // ⭐ 幂等键取 shareId：同一份额无论重跑多少次都只放一次款
        ledgerService.releaseShare(orderId, escrowId, shareId, takerId, amount, "SHARE:" + shareId);

        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("""
                UPDATE escrow_share
                   SET status = 'RELEASED', released_amount = ?, released_at = ?,
                       version = version + 1, updated_at = ?
                 WHERE id = ?
                """, amount, now, now, shareId);
    }

    /**
     * 重算托管单状态。
     *
     * <p>⭐ <b>如实反映进度</b>：全部份额 RELEASED 才是 {@code RELEASED}，
     * 只要还有钱留在托管池就是 {@code PARTIAL_RELEASED}。
     * ⛔ 一律写 RELEASED 会让对账看不出余额，而"单据说分完了、账本说还有钱"
     * 是资金系统里最难查的一类问题。
     */
    private String refreshEscrowStatus(Long escrowId, BigDecimal escrowAmount) {
        Integer pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM escrow_share
                 WHERE escrow_id = ? AND status IN ('PENDING', 'FROZEN', 'DISPUTE_FROZEN', 'PARTIAL_RELEASED')
                """, Integer.class, escrowId);

        BigDecimal releasedSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(released_amount), 0) FROM escrow_share WHERE escrow_id = ?",
                BigDecimal.class, escrowId);
        BigDecimal released = releasedSum == null ? BigDecimal.ZERO : releasedSum;

        String status;
        if (pending != null && pending == 0 && released.compareTo(escrowAmount) >= 0) {
            status = "RELEASED";
        } else if (released.compareTo(BigDecimal.ZERO) > 0) {
            status = "PARTIAL_RELEASED";
        } else {
            status = "FROZEN";
        }

        jdbc.update("""
                UPDATE escrow SET released_amount = ?, status = ?, updated_at = NOW(3) WHERE id = ?
                """, released, status, escrowId);
        return status;
    }
}