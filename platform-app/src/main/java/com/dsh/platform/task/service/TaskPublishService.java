package com.dsh.platform.task.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.task.statemachine.TaskEvent;
import com.dsh.platform.task.statemachine.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 任务发布。
 *
 * <p>发布 ≠ 创建：创建只落草稿，发布才进入审核流程。
 * 中间隔一步的价值是发单方可以先存着、反复改，不必一次填完。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskPublishService {

    private final TaskStateMachineService sm;
    private final JdbcTemplate jdbc;

    /** 提交发布：DRAFT → PENDING_AUDIT。 */
    public TaskStatus publish(Long taskId, Long publisherId) {
        requireOwner(taskId, publisherId);
        return sm.fire(taskId, TaskEvent.SUBMIT_PUBLISH, publisherId,
                TaskStateMachineService.OP_PUBLISHER, "提交发布");
    }

    /** 驳回后修改重提：AUDIT_REJECTED → PENDING_AUDIT。 */
    public TaskStatus resubmit(Long taskId, Long publisherId) {
        requireOwner(taskId, publisherId);
        return sm.fire(taskId, TaskEvent.RESUBMIT, publisherId,
                TaskStateMachineService.OP_PUBLISHER, "修改后重新提交");
    }

    /**
     * 校验是本人发的任务。
     *
     * <p>⚠️ 这里返回 {@link ErrorCode#NOT_TASK_OWNER} 而不是"任务不存在"：
     * 任务本身是公开可见的（大厅里人人能看），没必要隐藏它的存在。
     * 但 ⛔ 其他<b>不可见资源</b>（如订单）应统一返回 404 —— 见 UserDirectoryService 的注释。
     */
    private void requireOwner(Long taskId, Long publisherId) {
        java.util.List<Long> owners = jdbc.queryForList(
                "SELECT publisher_id FROM task WHERE id = ?", Long.class, taskId);
        if (owners.isEmpty()) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        if (!owners.get(0).equals(publisherId)) {
            throw new BizException(ErrorCode.NOT_TASK_OWNER);
        }
    }
}