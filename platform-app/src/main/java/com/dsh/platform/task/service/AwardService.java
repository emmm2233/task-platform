package com.dsh.platform.task.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.common.db.RowValues;
import com.dsh.platform.task.dto.AwardResult;
import com.dsh.platform.task.statemachine.TaskEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 中标（竞拍模式的发单方选定接单方）。
 *
 * <p>四件事，顺序不可换：
 * <pre>
 *   ① 锁任务行          SELECT ... FOR UPDATE —— 并发中标只有一个能过
 *   ② 幂等命中          同 key 直接返回首次结果（⭐ 必须在状态校验【之前】）
 *   ③ 状态与归属校验    只有 PENDING_SELECT / BIDDING 可中标；只有发单方能决定
 *   ④ 超发拦截 + 落库    Σ中标金额 + 佣金 ≤ 托管额
 * </pre>
 *
 * <p>⭐ <b>为什么幂等校验必须在状态校验之前</b>：第一次中标已经把任务推到
 * {@code PENDING_START}，第二次同 key 请求若先查状态就会被拒 ——
 * 而幂等的语义是"<b>返回首次结果</b>"，不是"报错"。弱网重试是很常见的。
 *
 * <p>⭐ <b>幂等键与顺序无关</b>：{@code taskId + awardMode + 排序后的 bidIds 哈希}。
 * 同样一组中标方，选择顺序不同不该产生两笔订单。
 *
 * <p>⚠️ 职责边界：本服务<b>不创建</b> {@code sub_order} 与 {@code escrow} ——
 * 两者的 {@code order_id} 都非空，而 {@code biz_order} 是资金视角的订单、
 * 在托管阶段（T17）才产生。在 T15 硬造没有 order_id 的子订单只会让资金链路对不上账，
 * 这也是 T13 抢单里踩过并纠正过的同一条边界。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AwardService {

    private final TaskStateMachineService sm;
    private final JdbcTemplate jdbc;

    @Transactional
    public AwardResult award(Long taskId, Long publisherId, List<Long> bidIds) {
        if (bidIds == null || bidIds.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请选择至少一个中标报价");
        }

        // ① 锁任务行
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT publisher_id, status, award_mode, grant_mode, escrow_amount, commission_snapshot "
                        + "FROM task WHERE id = ? FOR UPDATE", taskId);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        Map<String, Object> t = rows.get(0);
        String awardMode = String.valueOf(t.get("award_mode"));
        String key = idempotentKey(taskId, awardMode, bidIds);

        // ② 幂等命中 —— ⭐ 必须在状态校验之前，否则重试会得到"状态不对"而不是首次结果
        List<Long> existed = jdbc.queryForList(
                "SELECT id FROM award WHERE idempotent_key = ? ORDER BY id", Long.class, key);
        if (!existed.isEmpty()) {
            log.info("中标幂等命中 taskId={} key={}", taskId, key);
            return AwardResult.builder().awardIds(existed).idempotentKey(key)
                    .idempotent(true).totalAwarded(sumOf(existed)).build();
        }

        // ③ 状态与归属
        if (!String.valueOf(t.get("publisher_id")).equals(String.valueOf(publisherId))) {
            throw new BizException(ErrorCode.NOT_TASK_OWNER, "只有发单方可以决定中标方");
        }
        String status = String.valueOf(t.get("status"));
        if (!"PENDING_SELECT".equals(status) && !"BIDDING".equals(status)) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID,
                    "当前状态（" + status + "）不能中标");
        }
        if (!"SINGLE".equals(awardMode) && bidIds.size() < 2) {
            throw new BizException(ErrorCode.PARAM_INVALID, "多选中标需要至少 2 个中标方");
        }
        if ("SINGLE".equals(awardMode) && bidIds.size() != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单选中标只能选 1 个中标方");
        }

        // 取报价并校验归属与状态
        List<Map<String, Object>> bids = new ArrayList<>();
        for (Long bidId : bidIds) {
            List<Map<String, Object>> b = jdbc.queryForList(
                    "SELECT id, bidder_id, quote_amount, status FROM task_bid WHERE id = ? AND task_id = ?",
                    bidId, taskId);
            if (b.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "报价 " + bidId + " 不属于该任务");
            }
            if (!"QUOTED".equals(String.valueOf(b.get(0).get("status")))) {
                throw new BizException(ErrorCode.BID_CLOSED,
                        "报价 " + bidId + " 当前状态为 " + b.get(0).get("status") + "，不能中标");
            }
            bids.add(b.get(0));
        }

        // ④ 超发拦截
        BigDecimal sum = bids.stream()
                .map(b -> (BigDecimal) b.get("quote_amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commission = t.get("commission_snapshot") == null
                ? BigDecimal.ZERO : (BigDecimal) t.get("commission_snapshot");
        BigDecimal escrow = t.get("escrow_amount") == null
                ? BigDecimal.ZERO : (BigDecimal) t.get("escrow_amount");
        if (escrow.compareTo(BigDecimal.ZERO) > 0
                && !checkNoOverIssue(escrow, sum, commission)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "中标总额（" + sum.toPlainString() + "）加佣金（" + commission.toPlainString()
                            + "）超过托管额（" + escrow.toPlainString() + "）");
        }

        // 落 award（用 RowValues 统一归一 —— 这是第 12 次防同一类 NOT NULL 问题）
        List<Long> awardIds = new ArrayList<>();
        for (Map<String, Object> b : bids) {
            Map<String, Object> c = RowValues.of(
                    "task_id", taskId,
                    "bid_id", b.get("id"),
                    "awardee_id", b.get("bidder_id"),
                    "awarded_amount", b.get("quote_amount"),
                    "award_mode", awardMode,
                    "grant_mode", String.valueOf(t.get("grant_mode")),
                    "awarded_by", publisherId,
                    "idempotent_key", key);
            String[] parts = RowValues.insertParts(c);
            jdbc.update("INSERT INTO award (" + parts[0] + ", created_at) VALUES (" + parts[1] + ", NOW(3))",
                    c.values().toArray());
            awardIds.add(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        }

        // 中标方 ACCEPTED；其余 QUOTED → REJECTED
        // ⭐ 落败的必须置 REJECTED：不置的话接单方会一直等一个不会来的中标通知
        for (Map<String, Object> b : bids) {
            jdbc.update("UPDATE task_bid SET status = 'ACCEPTED', updated_at = NOW(3) WHERE id = ?",
                    b.get("id"));
        }
        jdbc.update("""
                UPDATE task_bid SET status = 'REJECTED', updated_at = NOW(3)
                 WHERE task_id = ? AND status = 'QUOTED'
                """, taskId);

        // 推进状态机：待选标 → 待开始（提前选定的走 AWARD_EARLY）
        TaskEvent event = "BIDDING".equals(status) ? TaskEvent.AWARD_EARLY : TaskEvent.AWARD;
        sm.fire(taskId, event, publisherId, TaskStateMachineService.OP_PUBLISHER, "选定中标方");

        log.info("中标完成 taskId={} awardIds={} 总额={}", taskId, awardIds, sum.toPlainString());
        return AwardResult.builder().awardIds(awardIds).idempotentKey(key)
                .idempotent(false).totalAwarded(sum).build();
    }

    /**
     * 幂等键：{@code taskId | awardMode | 排序后的 bidIds}。
     *
     * <p>⭐ 对 bidIds <b>排序</b>是必须的 —— 否则"先选 A 再选 B"与"先选 B 再选 A"
     * 会算出两个键，同一组中标方产生两笔订单。
     */
    public String idempotentKey(Long taskId, String awardMode, List<Long> bidIds) {
        List<Long> sorted = new ArrayList<>(bidIds);
        java.util.Collections.sort(sorted);
        String raw = taskId + "|" + awardMode + "|" + sorted;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("幂等键计算失败", e);
        }
    }

    /**
     * 超发拦截：{@code Σ中标金额 + 佣金 ≤ 托管额}。
     *
     * <p>⭐ 资损防线。做成<b>纯函数</b>是为了能脱离 escrow 落库独立测试 ——
     * escrow 要挂在 {@code biz_order} 上（T17 才有），
     * ⛔ 不该因为"依赖还没就绪"就把这条规则推迟。
     *
     * <p>⛔ 判据是 {@code >} 而不是 {@code >=}：正好相等是合法的（发单方把托管额用满）。
     * 且金额比对用 {@link BigDecimal#compareTo}，不用 {@code equals} ——
     * {@code 10000.00} 与 {@code 10000} 的 scale 不同但数值相等，
     * 用 equals 会误判成超发（本项目在 T9 的预算配平上踩过同类陷阱）。
     */
    public boolean checkNoOverIssue(BigDecimal escrowAmount, BigDecimal sumAwarded,
                                    BigDecimal commission) {
        BigDecimal e = RowValues.nzDecimal(escrowAmount);
        BigDecimal s = RowValues.nzDecimal(sumAwarded);
        BigDecimal c = RowValues.nzDecimal(commission);
        return s.add(c).compareTo(e) <= 0;
    }

    private BigDecimal sumOf(List<Long> awardIds) {
        if (awardIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(awarded_amount), 0) FROM award WHERE id IN ("
                        + String.join(",", awardIds.stream().map(String::valueOf).toList()) + ")",
                BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }
}