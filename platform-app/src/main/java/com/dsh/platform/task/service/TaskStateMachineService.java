package com.dsh.platform.task.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.task.statemachine.TaskEvent;
import com.dsh.platform.task.statemachine.TaskStateMachine;
import com.dsh.platform.task.statemachine.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 状态机落库驱动 —— 把 T10 的纯表驱动状态机接到数据库上。
 *
 * <p>职责边界很清楚：
 * <ul>
 *   <li>{@link TaskStateMachine} 只回答"能不能迁、迁到哪"（纯函数，可穷举测试）；</li>
 *   <li>本类负责"改了状态之后库里要发生什么"：锁行、改 {@code task.status}、
 *       同步审核字段、写 {@code task_status_log}。</li>
 * </ul>
 * 分开的好处：状态规则可以脱离 Spring 上下文测试；而落库逻辑集中在一处，
 * ⛔ 不会出现"某个入口忘了写轨迹"。
 *
 * <p>⭐ 三条硬约束：
 * <ol>
 *   <li><b>{@code SELECT ... FOR UPDATE}</b> —— 并发下两个请求同时读到同一状态时，
 *       只有一个能改成功，另一个会拿到更新后的状态并被状态机拒绝。
 *       ⛔ 不加行锁就会出现"两次抢单都判定合法"这类问题（§5.5.1 的抢单同理）；</li>
 *   <li><b>被拒的迁移不写任何东西</b> —— 先算目标状态再改库，算不出来就抛，绝不半途而废；</li>
 *   <li><b>每次迁移必写轨迹</b> —— 争议时要能回答"这条任务都经历了什么、谁做的、为什么"。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskStateMachineService {

    private final TaskStateMachine machine;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 操作人类型（写进轨迹，便于区分人工动作与系统动作）。 */
    public static final String OP_PUBLISHER = "PUBLISHER";
    public static final String OP_TAKER = "TAKER";
    public static final String OP_OPERATOR = "OPERATOR";
    public static final String OP_SYSTEM = "SYSTEM";

    /**
     * 驱动一次状态迁移。
     *
     * @param taskId       任务
     * @param event        触发事件
     * @param operatorId   操作人（系统动作用 0）
     * @param operatorType PUBLISHER / TAKER / OPERATOR / SYSTEM
     * @param reason       原因（驳回、裁决等场景必填，其余可为 null）
     * @return 迁移后的状态
     */
    @Transactional
    public TaskStatus fire(Long taskId, TaskEvent event, Long operatorId,
                           String operatorType, String reason) {
        // ① 锁行读取当前状态：并发下只有一个请求能基于同一旧状态做出迁移
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status FROM task WHERE id = ? FOR UPDATE", taskId);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        String raw = String.valueOf(rows.get(0).get("status"));
        TaskStatus from;
        try {
            from = TaskStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            // 库里出现了状态机不认识的值 —— 说明有人绕过应用改了库或迁移未同步
            throw new IllegalStateException("任务 " + taskId + " 的状态非法：" + raw);
        }

        // ② 先算目标状态。算不出来会抛异常 → 事务回滚 → 不留下任何脏写
        TaskStatus to = machine.next(from, event);

        LocalDateTime now = LocalDateTime.now(clock);

        // ③ 改主表状态 + 同步审核字段
        jdbc.update("""
                UPDATE task
                   SET status = ?, updated_at = ?,
                       audit_status = COALESCE(?, audit_status),
                       audit_by     = COALESCE(?, audit_by),
                       audit_at     = CASE WHEN ? IS NULL THEN audit_at ELSE ? END,
                       audit_reason = COALESCE(?, audit_reason)
                 WHERE id = ?
                """,
                to.name(), now,
                auditStatusOf(event),
                OP_OPERATOR.equals(operatorType) ? operatorId : null,
                auditStatusOf(event), now,
                (reason != null && !reason.isBlank()) ? reason : null,
                taskId);

        // ④ 写轨迹（每次迁移一行，不可省）
        jdbc.update("""
                INSERT INTO task_status_log
                  (task_id, from_status, to_status, from_group, to_group, event,
                   operator_id, operator_type, reason, trace_id, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                taskId, from.name(), to.name(),
                from.getGroup().name(), to.getGroup().name(),
                event.name(), operatorId, operatorType,
                reason == null ? "" : reason,
                // ⛔ trace_id 是 NOT NULL DEFAULT ''，显式传 null 会报 1048。
                //    定时任务、MQ 消费等非 web 场景下 MDC 里没有 traceId，必须归一成空串。
                nz(org.slf4j.MDC.get("traceId")), now);

        log.info("任务状态迁移 taskId={} {} --{}--> {} by {}({})",
                taskId, from, event, to, operatorId, operatorType);
        return to;
    }

    /** null → 空串（NOT NULL 文本列不能用 null 写入）。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 事件 → 审核状态的映射。
     *
     * <p>返回 null 表示"本次迁移不动审核字段"（如托管支付、履约类迁移）——
     * 用 {@code COALESCE} 在 SQL 里保持原值，⛔ 不要写成 null 直接覆盖。
     */
    private static String auditStatusOf(TaskEvent event) {
        return switch (event) {
            case SUBMIT_PUBLISH, RESUBMIT -> "PENDING";
            case AUDIT_REJECT -> "REJECTED";
            case AUDIT_PASS_PAID_GRAB, AUDIT_PASS_PAID_BID, AUDIT_PASS_FREE_GRAB,
                 AUDIT_PASS_FREE_BID, AUDIT_PASS_ESCROW -> "PASSED";
            default -> null;
        };
    }
}
