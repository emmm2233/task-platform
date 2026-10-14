package com.dsh.platform.fund.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.common.db.RowValues;
import com.dsh.platform.fund.dto.EscrowResult;
import com.dsh.platform.task.statemachine.TaskEvent;
import com.dsh.platform.task.service.TaskStateMachineService;
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
 * 托管（发单方把钱冻结在第三方渠道）。
 *
 * <p>⭐ <b>为什么托管是整个平台最重要的信任机制</b>：没有它，接单方不敢先干活
 * （干完不给钱）、发单方不敢先付钱（给了不干活）。托管把"谁先谁后"这个死结
 * 交给一个双方都信任的中间态：钱先冻结，验收通过才放。
 *
 * <p>⛔ <b>资金不过平台账户</b>：库里刻意<b>没有</b> {@code wallet} /
 * {@code wallet_transaction} 表，托管在渠道侧（{@code channel_order_no}）。
 * 平台用自己的账户归集客户资金会构成「二清」，是合规红线。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>⭐ <b>金额必须严格相等</b>——少付了后面分账不够分；多付了要走退款分支，
 *       而"退多付的钱"是最容易出错的资金路径，直接从源头堵掉；</li>
 *   <li>⭐ <b>以渠道单号幂等</b>——渠道超时重推是常态，不是异常。重复回调必须只记一次，
 *       否则一笔钱会变成两笔托管，后面分账直接穿仓；</li>
 *   <li><b>状态按 grant_mode 分流</b>——抢单进 RECRUITING、竞拍进 BIDDING，
 *       这正是状态机把「到 RECRUITING 还是 BIDDING」拆成两个事件的原因。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EscrowService {

    private final TaskStateMachineService sm;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 托管支付成功（渠道回调入口）。
     *
     * @param channelOrderNo 渠道单号 —— 幂等键
     */
    @Transactional
    public EscrowResult fund(Long taskId, Long publisherId, BigDecimal paidAmount,
                             String channelOrderNo, String channel) {
        if (channelOrderNo == null || channelOrderNo.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "渠道单号不能为空（它是幂等键）");
        }

        // ① 幂等：同渠道单号只记一次
        List<Map<String, Object>> existed = jdbc.queryForList(
                "SELECT id, order_id, amount, status FROM escrow WHERE channel_order_no = ?", channelOrderNo);
        if (!existed.isEmpty()) {
            Map<String, Object> e = existed.get(0);
            log.info("托管幂等命中 channelOrderNo={} escrowId={}", channelOrderNo, e.get("id"));
            return EscrowResult.builder()
                    .orderId(((Number) e.get("order_id")).longValue())
                    .escrowId(((Number) e.get("id")).longValue())
                    .amount((BigDecimal) e.get("amount"))
                    .status(String.valueOf(e.get("status")))
                    .idempotent(true)
                    .build();
        }

        // ② 锁任务行读状态
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT publisher_id, status, grant_mode, escrow_amount FROM task WHERE id = ? FOR UPDATE",
                taskId);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        Map<String, Object> t = rows.get(0);

        if (!String.valueOf(t.get("publisher_id")).equals(String.valueOf(publisherId))) {
            throw new BizException(ErrorCode.NOT_TASK_OWNER, "只有发单方可以托管自己的任务");
        }
        String status = String.valueOf(t.get("status"));
        if (!"PENDING_ESCROW".equals(status)) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID,
                    "当前状态（" + status + "）不能托管");
        }

        // ③ 金额严格相等 —— ⛔ 用 compareTo 不用 equals（scale 不同但数值相等是合法的）
        BigDecimal need = RowValues.nzDecimal((BigDecimal) t.get("escrow_amount"));
        BigDecimal paid = RowValues.nzDecimal(paidAmount);
        if (paid.compareTo(need) != 0) {
            throw new BizException(ErrorCode.AMOUNT_MISMATCH,
                    "应付 " + need.toPlainString() + "，实付 " + paid.toPlainString());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String grantMode = String.valueOf(t.get("grant_mode"));

        // ④ 建 biz_order（资金视角的订单；子订单在履约阶段挂到它上面）
        Map<String, Object> orderCols = RowValues.of(
                "order_no", "O" + Long.toString(System.nanoTime(), 36),
                "task_id", taskId,
                "publisher_id", publisherId,
                "total_amount", paid,
                "sub_order_count", 0,
                "settled_count", 0,
                "created_at", now,
                "updated_at", now);
        String[] op = RowValues.insertParts(orderCols);
        jdbc.update("INSERT INTO biz_order (" + op[0] + ") VALUES (" + op[1] + ")",
                orderCols.values().toArray());
        Long orderId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // ⑤ 建 escrow
        Map<String, Object> escCols = RowValues.of(
                "escrow_no", "ES" + Long.toString(System.nanoTime(), 36),
                "order_id", orderId,
                "publisher_id", publisherId,
                "amount", paid,
                "released_amount", BigDecimal.ZERO,
                "refunded_amount", BigDecimal.ZERO,
                "status", "FROZEN",
                "channel", channel == null ? "" : channel,
                "channel_order_no", channelOrderNo,
                "paid_at", now,
                "created_at", now,
                "updated_at", now);
        String[] ep = RowValues.insertParts(escCols);
        jdbc.update("INSERT INTO escrow (" + ep[0] + ") VALUES (" + ep[1] + ")",
                escCols.values().toArray());
        Long escrowId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // ⑥ 状态机分流：抢单 → RECRUITING，竞拍 → BIDDING
        TaskEvent event = "GRAB".equals(grantMode)
                ? TaskEvent.ESCROW_PAID_GRAB : TaskEvent.ESCROW_PAID_BID;
        sm.fire(taskId, event, publisherId, TaskStateMachineService.OP_SYSTEM, "托管支付成功");

        log.info("托管成功 taskId={} orderId={} escrowId={} 金额={} 渠道单号={}",
                taskId, orderId, escrowId, paid.toPlainString(), channelOrderNo);

        return EscrowResult.builder().orderId(orderId).escrowId(escrowId)
                .amount(paid).status("FROZEN").idempotent(false).build();
    }

    /**
     * 托管超时未支付 → 已取消。
     *
     * <p>⭐ 必须有这条：发单方点了发布却放着不付钱，任务会一直挂在"待托管"，
     * 既占着大厅的位置，也让审核通过的资源白费。
     */
    @Transactional
    public void cancelOnTimeout(Long taskId, String reason) {
        sm.fire(taskId, TaskEvent.ESCROW_TIMEOUT_CANCEL, 0L,
                TaskStateMachineService.OP_SYSTEM, reason);
    }
}