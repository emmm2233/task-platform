package com.dsh.platform.fund.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.common.db.RowValues;
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
 * 验收 → 分账：把已有能力串成一条真实链路。
 *
 * <p>⭐ <b>本类的价值在于"接通"而不是"新增"</b>。到这里为止各部分都已单独验证过：
 * 状态机能判定 {@code PENDING_ACCEPT + ACCEPT → COMPLETED}（T10）、
 * 托管与账本已能收钱入池（T16）、分账编排已能按份额释放（T17）——
 * 但 <b>{@link SettlementService} 一个调用方都没有</b>。
 * 一个功能齐全却没人调用的服务，等于没做。
 *
 * <h2>为什么整条链路必须在同一个事务里</h2>
 * ⛔ <b>"状态改了、分账失败了"是最坏的状态</b>：用户看到"已完成"，钱却还在托管池。
 * 那既要人工介入退款，又无法从状态判断该不该重放。所以这里要么全成、要么全不动。
 *
 * <h2>份额是怎么来的</h2>
 * Phase 1 一个任务一个 {@code WHOLE} 结算单元（份额 = 托管额），
 * 所以验收时按需补齐 {@code sub_order → delivery_unit → escrow_share} 三级单据。
 * ⚠️ 真实链路里 {@code sub_order} 应在指派（抢单/中标）时就建；
 * 这里"按需补齐"是为了让验收端到端可用，等履约域落齐后应把创建点前移。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcceptanceService {

    private final TaskStateMachineService sm;
    private final SettlementService settlementService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 验收通过并发起分账。
     *
     * @param actorId 发单方或接单方（⛔ 第三方不可替人验收）
     */
    @Transactional
    public void accept(Long taskId, Long actorId, String remark) {
        // ① 锁任务行
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT publisher_id, assignee_id, status, escrow_amount
                  FROM task WHERE id = ? FOR UPDATE
                """, taskId);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        Map<String, Object> t = rows.get(0);

        boolean isPublisher = String.valueOf(t.get("publisher_id")).equals(String.valueOf(actorId));
        boolean isTaker = t.get("assignee_id") != null
                && String.valueOf(t.get("assignee_id")).equals(String.valueOf(actorId));
        if (!isPublisher && !isTaker) {
            throw new BizException(ErrorCode.NOT_TASK_OWNER, "只有发单方或接单方可以确认验收");
        }

        // ② 状态机推进（状态不对会在这里抛，且事务回滚 → 钱一分不动）
        sm.fire(taskId, TaskEvent.ACCEPT, actorId,
                isPublisher ? TaskStateMachineService.OP_PUBLISHER : TaskStateMachineService.OP_TAKER,
                remark);

        // ③ 分账：补齐份额并释放
        settleIfEscrowed(taskId, (BigDecimal) t.get("escrow_amount"));
    }

    /**
     * 有托管就释放，没有就跳过。
     *
     * <p>⚠️ 免托管任务（低风险分类）没有 escrow，验收后不该报错 ——
     * 所以这里按"有无托管"分支，而不是要求托管必须存在。
     */
    private void settleIfEscrowed(Long taskId, BigDecimal escrowAmount) {
        List<Map<String, Object>> e = jdbc.queryForList("""
                SELECT e.id AS escrow_id, e.order_id, e.amount
                  FROM escrow e JOIN biz_order o ON o.id = e.order_id
                 WHERE o.task_id = ?
                 ORDER BY e.id DESC LIMIT 1
                """, taskId);
        if (e.isEmpty()) {
            log.info("任务 {} 无托管单，跳过分账（免托管任务）", taskId);
            return;
        }
        Long escrowId = ((Number) e.get(0).get("escrow_id")).longValue();
        Long orderId = ((Number) e.get(0).get("order_id")).longValue();
        BigDecimal amount = (BigDecimal) e.get(0).get("amount");

        Long takerId = jdbc.queryForObject("SELECT assignee_id FROM task WHERE id = ?", Long.class, taskId);
        ensureWholeShare(taskId, orderId, escrowId, takerId, amount);

        settlementService.settle(orderId, escrowId);
    }

    /** 任务截止时间（子订单的 deadline 是 NOT NULL，需继承它）。 */
    private LocalDateTime taskDeadline(Long taskId) {
        return jdbc.queryForObject("SELECT deadline FROM task WHERE id = ?", LocalDateTime.class, taskId);
    }

    /**
     * 取（必要时补建）中标记录。
     *
     * <p>⭐ 为什么补建而不是绕过：{@code award} 是"这单派给了谁、按什么方式派"的唯一记录。
     * 抢单任务若没有 award，资金链路就说不清这笔钱是依据什么派出去的 ——
     * 而争议时第一个要回答的就是这个问题。
     *
     * <p>幂等键取 {@code ASSIGN:<taskId>}，重复调用不会建出多条。
     */
    private Long ensureAward(Long taskId, Long takerId, BigDecimal amount, LocalDateTime now) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM award WHERE task_id = ? ORDER BY id LIMIT 1", Long.class, taskId);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        Map<String, Object> task = jdbc.queryForMap(
                "SELECT award_mode, grant_mode, publisher_id FROM task WHERE id = ?", taskId);
        Map<String, Object> a = RowValues.of(
                "task_id", taskId,
                "awardee_id", takerId,
                "awarded_amount", amount,
                "award_mode", String.valueOf(task.get("award_mode")),
                "grant_mode", String.valueOf(task.get("grant_mode")),
                "awarded_by", task.get("publisher_id"),
                "idempotent_key", "ASSIGN:" + taskId,
                "created_at", now);
        String[] p = RowValues.insertParts(a);
        jdbc.update("INSERT INTO award (" + p[0] + ") VALUES (" + p[1] + ")", a.values().toArray());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        log.info("已补建中标记录 taskId={} awardId={}（抢单路径无 award，语义上即一次指派）", taskId, id);
        return id;
    }

    /**
     * 确保存在一条 {@code WHOLE} 份额（Phase 1 一个任务一个结算单元）。
     *
     * <p>⚠️ 三级单据的创建顺序不能变：{@code sub_order} → {@code delivery_unit} → {@code escrow_share}，
     * 因为后两者要引用前者的 id。
     */
    private void ensureWholeShare(Long taskId, Long orderId, Long escrowId,
                                  Long takerId, BigDecimal amount) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM escrow_share WHERE escrow_id = ?", Integer.class, escrowId);
        if (exists != null && exists > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);

        // ⚠️ sub_order.award_id 是 NOT NULL —— DDL 期望【每个子订单都源自一次中标】。
        //    但抢单路径本来没有 award 记录，所以这里补齐一条：
        //    抢单在语义上就是"一次即时的指派"，给它留一条 award 才是自洽的
        //    （也让资金链路能回答"这单是怎么派出去的"）。
        //    ⛔ 不要为了绕过非空约束填 0 或 NULL —— 那会让审计与对账无从追溯。
        Long awardId = ensureAward(taskId, takerId, amount, now);

        // 子订单
        Map<String, Object> so = RowValues.of(
                "sub_order_no", "S" + Long.toString(System.nanoTime(), 36),
                "order_id", orderId,
                "task_id", taskId,
                "taker_id", takerId,
                "award_id", awardId,
                "amount", amount,
                "status", "COMPLETED",
                // ⚠️ sub_order.deadline 是 NOT NULL 无默认值 —— 继承任务截止时间。
                //    这里是"按需补齐单据"的路径，所以直接沿用任务的 deadline；
                //    等履约域落齐、子订单在指派时创建，它会取当时的约定时间。
                "deadline", taskDeadline(taskId),
                "modify_round", 0,
                "dispute_frozen", 0,
                "version", 0,
                "created_at", now,
                "updated_at", now);
        String[] p1 = RowValues.insertParts(so);
        jdbc.update("INSERT INTO sub_order (" + p1[0] + ") VALUES (" + p1[1] + ")", so.values().toArray());
        Long subOrderId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // 结算单元（Phase 1 一律 WHOLE）
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

        // 份额
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
