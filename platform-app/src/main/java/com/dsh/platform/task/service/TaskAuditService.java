package com.dsh.platform.task.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.task.statemachine.TaskEvent;
import com.dsh.platform.task.statemachine.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务审核流转（机审 / 人审 / 托管）。
 *
 * <p>⭐ 两条分流规则：
 * <ol>
 *   <li><b>审核级别按分类走</b> —— {@code task_category.audit_level = HUMAN} 的分类
 *       （教育、生活服务、市场营销、公益）<b>不允许机审直接放行</b>。
 *       这条规则必须在这里挡住，⛔ 不能指望"机审脚本自己记得跳过"；</li>
 *   <li><b>目标状态按 grant_mode 走</b> —— 状态机要求 {@code (状态,事件)} 唯一映射，
 *       所以拆成两个事件，由本类读 {@code task.grant_mode} 后选择投递哪个。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAuditService {

    private final TaskStateMachineService sm;
    private final JdbcTemplate jdbc;

    /**
     * 通用入口：按事件名驱动一次迁移（供运营端与其他模块复用）。
     *
     * <p>⚠️ 事件名非法时抛业务异常而不是 IllegalArgument —— 它来自请求参数，
     * 属于"用户输入错误"而非"程序 bug"。
     */
    public TaskStatus fire(Long taskId, String eventName, Long operatorId, String reason) {
        TaskEvent event;
        try {
            event = TaskEvent.valueOf(eventName);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知的状态事件：" + eventName);
        }
        return sm.fire(taskId, event, operatorId, TaskStateMachineService.OP_OPERATOR, reason);
    }

    /**
     * 人工审核通过（运营端调用）。
     *
     * <p>当前统一走「待托管」—— 发布即托管与免托管两种模式在 Phase 1 暂不开放，
     * 待 T17 托管能力就绪后按 {@code task.escrow_mode} 分流到
     * {@code AUDIT_PASS_PAID_*} / {@code AUDIT_PASS_FREE_*} 事件。
     */
    public TaskStatus approve(Long taskId, Long operatorId, String reason) {
        return sm.fire(taskId, TaskEvent.AUDIT_PASS_ESCROW, operatorId,
                TaskStateMachineService.OP_OPERATOR, reason);
    }

    /**
     * 机审通过。
     *
     * <p>⛔ 仅限 {@code audit_level = MACHINE} 的分类；高风险分类必须走人工，
     * 否则等于把教育、线下服务这类容易出问题的品类直接放出去了。
     */
    public TaskStatus machineApprove(Long taskId, Long operatorId) {
        String level = auditLevelOf(taskId);
        if (!"MACHINE".equals(level)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "该分类需要人工审核，机审不能放行");
        }
        return sm.fire(taskId, TaskEvent.AUDIT_PASS_ESCROW, operatorId,
                TaskStateMachineService.OP_SYSTEM, "机审通过");
    }

    /** 驳回：⛔ 必须给出原因，否则发单方不知道改什么。 */
    public TaskStatus reject(Long taskId, String reason, Long operatorId) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "驳回必须说明原因");
        }
        return sm.fire(taskId, TaskEvent.AUDIT_REJECT, operatorId,
                TaskStateMachineService.OP_OPERATOR, reason);
    }

    /**
     * 托管支付成功回调 → 按 grant_mode 分流。
     *
     * <p>⭐ 这正是 T10 把「→ RECRUITING / BIDDING」拆成两个事件的原因：
     * 状态机保持无歧义，由这里读 {@code grant_mode} 决定投递哪个。
     */
    public TaskStatus escrowPaid(Long taskId, Long operatorId) {
        String grantMode = jdbc.queryForObject(
                "SELECT grant_mode FROM task WHERE id = ?", String.class, taskId);
        TaskEvent event = "GRAB".equals(grantMode)
                ? TaskEvent.ESCROW_PAID_GRAB
                : TaskEvent.ESCROW_PAID_BID;
        return sm.fire(taskId, event, operatorId, TaskStateMachineService.OP_SYSTEM, "托管支付成功");
    }

    private String auditLevelOf(Long taskId) {
        List<String> levels = jdbc.queryForList("""
                SELECT c.audit_level
                  FROM task t JOIN task_category c ON c.id = t.category_id
                 WHERE t.id = ?
                """, String.class, taskId);
        if (levels.isEmpty()) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        return levels.get(0);
    }
}