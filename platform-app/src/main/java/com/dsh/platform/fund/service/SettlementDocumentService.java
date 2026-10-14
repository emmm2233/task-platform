package com.dsh.platform.fund.service;

import com.dsh.platform.common.db.RowValues;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 结算单据的"按需补齐"。
 *
 * <p>⭐ <b>本类是从 AcceptanceService 与 DeliveryService 里抽出来的</b>。
 * 那两处各有一份 {@code ensureAward} / {@code ensureSubOrder} 实现 ——
 * 语义完全相同，代码却有两份。抽出来的直接理由是上一轮的提交信息里
 * 我自己标注了这条风险：<b>下次改动只改一处，两边行为就会不一致</b>。
 *
 * <h2>为什么会有"按需补齐"这种东西</h2>
 * 正常链路里单据应在<b>指派时</b>（抢单/中标）创建：
 * <pre>
 *   托管 → biz_order
 *   指派 → award → sub_order
 *   验收 → delivery_unit → escrow_share
 * </pre>
 * 但履约域是后补的，指派环节当时还没有 {@code biz_order} 可挂，
 * 所以改由"谁先跑到谁补齐"。⚠️ <b>这是过渡方案，不是终态</b> ——
 * 等把创建点前移到指派环节后，本类应退化为纯粹的查询 + 断言。
 *
 * <h2>三个 NOT NULL 的坑（都是踩出来的）</h2>
 * <ul>
 *   <li>{@code sub_order.award_id} —— DDL 期望每个子订单都源自一次中标。
 *       抢单路径本来没有 award，所以这里补一条：抢单在语义上就是
 *       "一次即时的指派"，留一条 award 才自洽，也让资金链路能回答
 *       "这笔钱是依据什么派出去的"。
 *       ⛔ <b>不要为了绕过非空约束填 0</b> —— 那会让审计与对账无从追溯；</li>
 *   <li>{@code sub_order.deadline} —— 继承任务截止时间；</li>
 *   <li>{@code escrow_share} 的 sub_order_id / unit_id —— 所以三者的
 *       创建顺序不能变：award → sub_order → delivery_unit → escrow_share。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementDocumentService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 确保 {@code award} 存在，返回其 id。
     *
     * <p>幂等键 {@code ASSIGN:<taskId>}，重复调用不会建出多条。
     */
    public Long ensureAward(Long taskId, Long takerId, BigDecimal amount,
                            String awardMode, String grantMode, Long publisherId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM award WHERE task_id = ? ORDER BY id LIMIT 1", Long.class, taskId);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> a = RowValues.of(
                "task_id", taskId,
                "awardee_id", takerId,
                "awarded_amount", amount,
                "award_mode", awardMode,
                "grant_mode", grantMode,
                "awarded_by", publisherId,
                "idempotent_key", "ASSIGN:" + taskId,
                "created_at", now);
        String[] p = RowValues.insertParts(a);
        jdbc.update("INSERT INTO award (" + p[0] + ") VALUES (" + p[1] + ")", a.values().toArray());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        log.info("已补建中标记录 taskId={} awardId={}（抢单路径无 award，语义上即一次指派）", taskId, id);
        return id;
    }

    /**
     * 确保 {@code sub_order} 存在，返回其 id；无 {@code biz_order}（免托管任务）时返回 null。
     *
     * <p>⚠️ 用 {@code WHERE task_id} 判存在 —— 两处调用者都可能先跑到，
     * 不判就会建出两条子订单，而"一个任务两个子订单"会让分账彻底对不上。
     */
    public Long ensureSubOrder(Long taskId, Long takerId) {
        List<Long> existing = jdbc.queryForList(
                "SELECT id FROM sub_order WHERE task_id = ? ORDER BY id LIMIT 1", Long.class, taskId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        Map<String, Object> t = jdbc.queryForMap("""
                SELECT publisher_id, award_mode, grant_mode, escrow_amount, deadline
                  FROM task WHERE id = ?
                """, taskId);

        // ⚠️ 用 queryForList 而不是 queryForObject：后者在 0 行时【抛异常】而不是返回 null。
        //    本项目已多次栽在"查不到 ≠ 查询失败"上。
        List<Long> orderIds = jdbc.queryForList(
                "SELECT id FROM biz_order WHERE task_id = ? ORDER BY id DESC LIMIT 1", Long.class, taskId);
        if (orderIds.isEmpty()) {
            // 免托管任务没有 biz_order —— 子订单挂在空单据上会让资金链路说不清，
            // 所以明确跳过，等免托管单据模型定下来再补。
            log.info("任务 {} 无 biz_order，暂不建子订单（免托管任务）", taskId);
            return null;
        }

        BigDecimal amount = (BigDecimal) t.get("escrow_amount");
        LocalDateTime now = LocalDateTime.now(clock);
        Long awardId = ensureAward(taskId, takerId, amount,
                String.valueOf(t.get("award_mode")), String.valueOf(t.get("grant_mode")),
                ((Number) t.get("publisher_id")).longValue());

        Map<String, Object> so = RowValues.of(
                "sub_order_no", "S" + Long.toString(System.nanoTime(), 36),
                "order_id", orderIds.get(0),
                "task_id", taskId,
                "taker_id", takerId,
                "award_id", awardId,
                "amount", amount,
                "status", "IN_PROGRESS",
                "deadline", t.get("deadline"),
                "modify_round", 0,
                "dispute_frozen", 0,
                "version", 0,
                "created_at", now,
                "updated_at", now);
        String[] p = RowValues.insertParts(so);
        jdbc.update("INSERT INTO sub_order (" + p[0] + ") VALUES (" + p[1] + ")", so.values().toArray());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        log.info("已建子订单 taskId={} subOrderId={}", taskId, id);
        return id;
    }

    /**
     * 确保存在一条 {@code WHOLE} 份额（Phase 1 一个任务一个结算单元）。
     *
     * <p>调用顺序不能变：{@code sub_order} → {@code delivery_unit} → {@code escrow_share}，
     * 因为后两者要引用前者的 id。
     */
    public void ensureWholeShare(Long taskId, Long orderId, Long escrowId,
                                 Long takerId, BigDecimal amount) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM escrow_share WHERE escrow_id = ?", Integer.class, escrowId);
        if (exists != null && exists > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);

        Long subOrderId = ensureSubOrder(taskId, takerId);
        if (subOrderId == null) {
            log.warn("任务 {} 无子订单，无法建份额（免托管任务）", taskId);
            return;
        }

        Map<String, Object> du = RowValues.of(
                "unit_no", "U" + Long.toString(System.nanoTime(), 36),
                "sub_order_id", subOrderId,
                "order_id", orderId,
                "unit_type", "WHOLE",
                "seq", 1,
                "name", "整体交付",
                "amount", amount,
                "status", "ACCEPTED",
                "created_at", now);
        String[] p2 = RowValues.insertParts(du);
        jdbc.update("INSERT INTO delivery_unit (" + p2[0] + ") VALUES (" + p2[1] + ")", du.values().toArray());
        Long unitId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Map<String, Object> sh = RowValues.of(
                "escrow_id", escrowId,
                "order_id", orderId,
                "sub_order_id", subOrderId,
                "unit_id", unitId,
                "taker_id", takerId,
                "share_amount", amount,
                "status", "PENDING",
                "version", 0,
                "created_at", now,
                "updated_at", now);
        String[] p3 = RowValues.insertParts(sh);
        jdbc.update("INSERT INTO escrow_share (" + p3[0] + ") VALUES (" + p3[1] + ")", sh.values().toArray());
        log.info("已补齐结算单据 taskId={} subOrderId={} unitId={} 份额={}", taskId, subOrderId, unitId, amount);
    }
}
