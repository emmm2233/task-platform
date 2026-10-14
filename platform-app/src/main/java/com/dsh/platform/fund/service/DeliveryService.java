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
 * 履约侧：接单方提交交付物、发单方驳回返工。
 *
 * <p>⭐ 与 {@link AcceptanceService} 合起来才是完整闭环：
 * <pre>
 *   IN_PROGRESS --提交交付物--> PENDING_ACCEPT
 *   PENDING_ACCEPT --驳回--> REVISION（轮次 +1）
 *   REVISION --重新交付--> PENDING_ACCEPT
 *   PENDING_ACCEPT --验收--> COMPLETED --分账--> 接单方到账
 * </pre>
 *
 * <h2>⭐ 为什么必须有修改轮次上限</h2>
 * 没有上限的话，发单方可以无限驳回、接单方永远拿不到钱 ——
 * 这是平台上最典型的权力失衡（钱在对方手里、活已经干完）。
 * 轮次用尽必须<b>自动收口到平台裁决</b>，而不是继续让双方拉锯。
 * ⛔ 但"用尽"不等于自动判接单方赢 —— 那会诱发"随便交一版逼验收"，
 * 所以本类只负责拒绝继续驳回并提示走争议，实体判断交给仲裁。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final TaskStateMachineService sm;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 提交交付物（首次交付或返工后重新交付）。 */
    @Transactional
    public void submit(Long taskId, Long actorId, String deliverableDesc) {
        Map<String, Object> t = lockTask(taskId);

        if (!String.valueOf(t.get("assignee_id")).equals(String.valueOf(actorId))) {
            throw new BizException(ErrorCode.NOT_TASK_OWNER, "只有接单方可以提交交付物");
        }
        String status = String.valueOf(t.get("status"));
        // 两种入口：首次交付与返工后重新交付 —— 状态机对它们用不同事件
        TaskEvent event = switch (status) {
            case "IN_PROGRESS" -> TaskEvent.SUBMIT_DELIVERY;
            case "REVISION" -> TaskEvent.RESUBMIT_DELIVERY;
            default -> throw new BizException(ErrorCode.TASK_STATUS_INVALID,
                    "当前状态（" + status + "）不能提交交付物");
        };

        ensureSubOrder(taskId, actorId, t);
        jdbc.update("UPDATE task SET deliverable_desc = ?, updated_at = NOW(3) WHERE id = ?",
                deliverableDesc == null ? "" : deliverableDesc, taskId);

        sm.fire(taskId, event, actorId, TaskStateMachineService.OP_TAKER, "提交交付物");
        log.info("交付物已提交 taskId={} 由状态 {} 触发 {}", taskId, status, event);
    }

    /**
     * 驳回并要求返工。
     *
     * <p>⭐ <b>理由必填</b>：没有理由的驳回，接单方不知道改什么，
     * 双方会陷入"改了还是不行"的死循环。
     */
    @Transactional
    public void reject(Long taskId, Long actorId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "驳回必须说明具体原因，否则接单方不知道改什么");
        }
        Map<String, Object> t = lockTask(taskId);
        if (!String.valueOf(t.get("publisher_id")).equals(String.valueOf(actorId))) {
            throw new BizException(ErrorCode.NOT_TASK_OWNER, "只有发单方可以驳回");
        }
        String status = String.valueOf(t.get("status"));
        if (!"PENDING_ACCEPT".equals(status)) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID,
                    "当前状态（" + status + "）不能驳回");
        }

        // ⭐ 轮次校验：用尽即拒绝，不再允许驳回
        int limit = t.get("modify_round_limit") == null ? 3 : ((Number) t.get("modify_round_limit")).intValue();
        Integer used = currentRound(taskId);
        if (used != null && used >= limit) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID,
                    "修改轮次已用尽（" + used + "/" + limit + "），请发起争议由平台裁决");
        }

        jdbc.update("""
                UPDATE sub_order SET modify_round = modify_round + 1, updated_at = NOW(3)
                 WHERE task_id = ?
                """, taskId);

        sm.fire(taskId, TaskEvent.REJECT, actorId, TaskStateMachineService.OP_PUBLISHER, reason);
        log.info("已驳回 taskId={} 轮次 {}/{}", taskId,
                used == null ? 1 : used + 1, limit);
    }

    // ------------------------------------------------------------------

    private Map<String, Object> lockTask(Long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT publisher_id, assignee_id, status, modify_round_limit, award_mode, grant_mode,
                       amount_hint, deadline
                  FROM (SELECT publisher_id, assignee_id, status, modify_round_limit, award_mode, grant_mode,
                               escrow_amount AS amount_hint, deadline
                          FROM task WHERE id = ?) x FOR UPDATE
                """, taskId);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        return rows.get(0);
    }

    /**
     * 取（必要时补建）中标记录。幂等键 ASSIGN:<taskId>，重复调用不会建出多条。
     */
    private Long ensureAward(Long taskId, Long takerId, Map<String, Object> task,
                             BigDecimal amount, LocalDateTime now) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM award WHERE task_id = ? ORDER BY id LIMIT 1", Long.class, taskId);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
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

    private Integer currentRound(Long taskId) {
        var v = jdbc.queryForList("SELECT modify_round FROM sub_order WHERE task_id = ?",
                Integer.class, taskId);
        return v.isEmpty() ? null : v.get(0);
    }

    /**
     * 确保子订单存在。
     *
     * <p>⚠️ 与 {@link AcceptanceService} 的"按需补齐"是同一件事的两半：
     * 谁先跑到谁建。真实链路里它应在指派（抢单/中标）时创建 ——
     * 已记在待办里，等履约域落齐后把创建点前移。
     * ⛔ 用 {@code WHERE task_id} 判存在，避免两处各建一条。
     */
    private void ensureSubOrder(Long taskId, Long takerId, Map<String, Object> task) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sub_order WHERE task_id = ?", Integer.class, taskId);
        if (n != null && n > 0) {
            return;
        }
        // ⚠️ 用 queryForList 而不是 queryForObject：后者在 0 行时【抛异常】而不是返回 null。
        //    这个坑在本项目里已经踩过多次（"查不到"与"查询失败"是两件事）。
        List<Long> orderIds = jdbc.queryForList("""
                SELECT o.id FROM biz_order o WHERE o.task_id = ? ORDER BY o.id DESC LIMIT 1
                """, Long.class, taskId);
        Long orderId = orderIds.isEmpty() ? null : orderIds.get(0);
        if (orderId == null) {
            // 免托管任务没有 biz_order —— 子订单挂在空单据上会让资金链路说不清，
            // 所以这里明确跳过，等托管或免托管单据模型定下来再补。
            log.info("任务 {} 无 biz_order，暂不建子订单（免托管任务）", taskId);
            return;
        }
        BigDecimal amount = (BigDecimal) task.get("amount_hint");
        LocalDateTime now = LocalDateTime.now(clock);

        // sub_order.award_id 是 NOT NULL，DDL 期望每个子订单都源自一次中标。
        // 抢单路径本来没有 award 记录，这里补齐一条（与 AcceptanceService 同一处理）：
        // 抢单语义上就是一次即时的指派，给它留一条 award 才自洽，
        // 也让资金链路能回答这笔钱是依据什么派出去的。
        // 不要为了绕过非空约束填 0，那会让审计与对账无从追溯。
        Long awardId = ensureAward(taskId, takerId, task, amount, now);

        Map<String, Object> so = RowValues.of(
                "sub_order_no", "S" + Long.toString(System.nanoTime(), 36),
                "order_id", orderId,
                "task_id", taskId,
                "taker_id", takerId,
                "award_id", awardId,
                "amount", amount,
                "status", "IN_PROGRESS",
                "deadline", task.get("deadline"),
                "modify_round", 0,
                "dispute_frozen", 0,
                "version", 0,
                "created_at", now,
                "updated_at", now);
        String[] p = RowValues.insertParts(so);
        jdbc.update("INSERT INTO sub_order (" + p[0] + ") VALUES (" + p[1] + ")", so.values().toArray());
        log.info("已建子订单 taskId={}（履约侧按需补齐）", taskId);
    }
}
