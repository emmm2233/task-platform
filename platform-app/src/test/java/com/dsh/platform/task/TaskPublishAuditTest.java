package com.dsh.platform.task;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.identity.dto.LoginResult;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.SmsCodeService;
import com.dsh.platform.task.dto.TaskCreateRequest;
import com.dsh.platform.task.service.TaskAuditService;
import com.dsh.platform.task.service.TaskCommandService;
import com.dsh.platform.task.service.TaskPublishService;
import com.dsh.platform.task.statemachine.TaskStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T11 · 任务发布与审核流转。
 *
 * <p>⭐ 本任务是状态机（T10）第一次<b>真正驱动业务落库</b>：
 * 之前 T10 只验证了"表里允许什么迁移"，这里要验证
 * "迁移时 {@code task.status} 被正确改写、{@code task_status_log} 留下完整轨迹"。
 *
 * <p>三条本任务必须守住的东西：
 * <ol>
 *   <li><b>非法迁移在库层也被拒</b> —— 状态机说不行就不行，且不留下任何脏写；</li>
 *   <li><b>驳回必填原因</b> —— 没有原因的驳回，发单方不知道改什么，等于死循环；</li>
 *   <li><b>轨迹完整</b> —— 每次迁移一行日志（from/to/事件/操作人/原因/分组），
 *       它是争议时回答"这条任务都经历了什么"的唯一依据。</li>
 * </ol>
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskPublishAuditTest {

    private static final Random RANDOM = new Random();

    @Autowired private TaskCommandService taskCommandService;
    @Autowired private TaskPublishService publishService;
    @Autowired private TaskAuditService auditService;
    @Autowired private AuthService authService;
    @Autowired private SmsCodeService smsCodeService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private Long publisherId;

    @BeforeAll
    void setUp() {
        var keys = redis.keys("sms:limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        String phone = "138" + String.format("%08d", RANDOM.nextInt(100_000_000));
        String ip = "10." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(254);
        String code = smsCodeService.send(phone, SmsScene.REGISTER, ip, "dev-" + RANDOM.nextLong());
        publisherId = authService.register(phone, "Passw0rd!", "发单方", code).getUserId();
    }

    /** 建一个 DRAFT 任务，返回 taskId。 */
    private Long draftTask(String categoryCode, String taskForm, String payMode, String grantMode) {
        TaskCreateRequest r = new TaskCreateRequest();
        r.setCategoryCode(categoryCode);
        r.setTaskForm(taskForm);
        r.setPayMode(payMode);
        r.setGrantMode(grantMode);
        r.setAwardMode("SINGLE");
        r.setTitle("测试任务 " + RANDOM.nextInt(100000));
        r.setDescription("用于验证发布与审核流转。");
        r.setBudgetMin(new BigDecimal("3000.00"));
        r.setBudgetMax(new BigDecimal("5000.00"));
        r.setPrice(new BigDecimal("3000.00"));
        r.setDeadline(LocalDateTime.now().plusDays(14));
        r.setAcceptDays(7);
        TaskCreateRequest.AcceptanceItem i = new TaskCreateRequest.AcceptanceItem();
        i.setItemName("功能可用");
        r.setAcceptanceItems(List.of(i));
        if ("OFFLINE".equals(taskForm)) {
            TaskCreateRequest.Location loc = new TaskCreateRequest.Location();
            loc.setProvince("河南省");
            loc.setCity("郑州市");
            loc.setAddress("金水区某处");
            loc.setLng(new BigDecimal("113.660000"));
            loc.setLat(new BigDecimal("34.800000"));
            r.setLocation(loc);
            r.setServiceTimeSlots(List.of("09:00-12:00"));
        }
        return taskCommandService.create(r, publisherId).getTaskId();
    }

    private String statusOf(Long taskId) {
        return jdbc.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId);
    }

    private int logCount(Long taskId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_status_log WHERE task_id = ?", Integer.class, taskId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 发布：DRAFT → PENDING_AUDIT，且写下一行状态轨迹")
    void publish_moves_draft_to_pending_audit() {
        Long taskId = draftTask("DEV", "ONLINE", "BID", "BID");
        assertThat(statusOf(taskId)).isEqualTo("DRAFT");

        publishService.publish(taskId, publisherId);

        assertThat(statusOf(taskId)).isEqualTo("PENDING_AUDIT");
        assertThat(logCount(taskId)).isEqualTo(1);

        var log = jdbc.queryForMap(
                "SELECT from_status, to_status, event, from_group, to_group, operator_type "
                        + "FROM task_status_log WHERE task_id = ?", taskId);
        assertThat(log.get("from_status")).isEqualTo("DRAFT");
        assertThat(log.get("to_status")).isEqualTo("PENDING_AUDIT");
        assertThat(log.get("event")).isEqualTo("SUBMIT_PUBLISH");
        // 分组也一并落库 —— 查询"某任务在履约域停留多久"不必再 join 状态表
        assertThat(log.get("from_group")).isEqualTo("DRAFT");
        assertThat(log.get("to_group")).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("⭐ 非发单方不能发布别人的任务")
    void only_publisher_can_publish() {
        Long taskId = draftTask("DEV", "ONLINE", "BID", "BID");

        assertThatThrownBy(() -> publishService.publish(taskId, publisherId + 99999))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_TASK_OWNER.getCode());
        assertThat(statusOf(taskId)).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("⭐ 非法迁移在库层也被拒，且不留下任何脏写")
    void illegal_transition_is_rejected_without_dirty_write() {
        Long taskId = draftTask("DEV", "ONLINE", "BID", "BID");
        publishService.publish(taskId, publisherId);   // → PENDING_AUDIT
        int logsBefore = logCount(taskId);

        // PENDING_AUDIT 阶段直接"验收通过" —— 状态机必须拒绝
        assertThatThrownBy(() -> auditService.fire(taskId, "ACCEPT", 1L, "越权尝试"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TASK_STATUS_INVALID.getCode());

        assertThat(statusOf(taskId)).isEqualTo("PENDING_AUDIT");
        assertThat(logCount(taskId))
                .as("⛔ 被拒的迁移不能留下轨迹行")
                .isEqualTo(logsBefore);
    }

    @Test
    @DisplayName("⭐ 驳回必填原因；有原因则进入 AUDIT_REJECTED，且原因被记录")
    void reject_requires_reason_and_is_recorded() {
        Long taskId = draftTask("DEV", "ONLINE", "BID", "BID");
        publishService.publish(taskId, publisherId);

        // ① 不给原因 → 拒
        assertThatThrownBy(() -> auditService.reject(taskId, null, 1L))
                .isInstanceOf(BizException.class);
        assertThat(statusOf(taskId)).isEqualTo("PENDING_AUDIT");

        // ② 给原因 → 通过
        auditService.reject(taskId, "描述涉嫌违规", 1L);
        assertThat(statusOf(taskId)).isEqualTo("AUDIT_REJECTED");

        String reason = jdbc.queryForObject(
                "SELECT reason FROM task_status_log WHERE task_id = ? ORDER BY id DESC LIMIT 1",
                String.class, taskId);
        assertThat(reason).isEqualTo("描述涉嫌违规");
        // 驳回原因同时写回任务本身，发单方在列表页就能看到，不必翻日志
        assertThat(jdbc.queryForObject(
                "SELECT audit_reason FROM task WHERE id = ?", String.class, taskId))
                .isEqualTo("描述涉嫌违规");
    }

    @Test
    @DisplayName("⭐ 驳回后可修改并重新提交：AUDIT_REJECTED → PENDING_AUDIT")
    void rejected_task_can_be_edited_and_resubmitted() {
        Long taskId = draftTask("DEV", "ONLINE", "BID", "BID");
        publishService.publish(taskId, publisherId);
        auditService.reject(taskId, "描述太简单", 1L);
        assertThat(statusOf(taskId)).isEqualTo("AUDIT_REJECTED");

        // 改一改再提交（内容修改由 TaskCommandService 的更新能力负责，这里只验证流转）
        jdbc.update("UPDATE task SET description = ? WHERE id = ?", "补充了详细需求与交付标准。", taskId);
        publishService.resubmit(taskId, publisherId);

        assertThat(statusOf(taskId)).isEqualTo("PENDING_AUDIT");
        assertThat(logCount(taskId)).isEqualTo(3);   // 发布 → 驳回 → 重提
    }

    @Test
    @DisplayName("⭐ 审核通过分流：低风险自动托管；高风险分类必须人工审核后才放行")
    void audit_pass_routes_by_category_and_escrow_mode() {
        // DEV 是 MACHINE 审核 + 未托管 → 进 PENDING_ESCROW
        Long autoTask = draftTask("DEV", "ONLINE", "BID", "BID");
        publishService.publish(autoTask, publisherId);
        auditService.approve(autoTask, 1L, "机审通过");
        assertThat(statusOf(autoTask)).isEqualTo("PENDING_ESCROW");

        // EDU 是 HUMAN 审核 —— 机审不能直接放行
        Long humanTask = draftTask("EDU", "OFFLINE", "FIXED", "GRAB");
        publishService.publish(humanTask, publisherId);
        assertThatThrownBy(() -> auditService.machineApprove(humanTask, 1L))
                .as("⛔ 高风险分类不允许机审直接放行")
                .isInstanceOf(BizException.class);
        assertThat(statusOf(humanTask)).isEqualTo("PENDING_AUDIT");

        // 人工审核放行 → 同样进 PENDING_ESCROW
        auditService.approve(humanTask, 1L, "人工审核通过");
        assertThat(statusOf(humanTask)).isEqualTo("PENDING_ESCROW");
    }

    @Test
    @DisplayName("审核通过后按 grant_mode 走对状态：竞拍 → BIDDING，抢单 → RECRUITING（托管支付后）")
    void escrow_paid_routes_by_grant_mode() {
        Long bidTask = draftTask("DEV", "ONLINE", "BID", "BID");
        publishService.publish(bidTask, publisherId);
        auditService.approve(bidTask, 1L, "通过");
        auditService.escrowPaid(bidTask, 1L);
        assertThat(statusOf(bidTask))
                .as("竞拍任务托管支付后应进入竞价中")
                .isEqualTo("BIDDING");

        Long grabTask = draftTask("DEV", "ONLINE", "FIXED", "GRAB");
        publishService.publish(grabTask, publisherId);
        auditService.approve(grabTask, 1L, "通过");
        auditService.escrowPaid(grabTask, 1L);
        assertThat(statusOf(grabTask))
                .as("抢单任务托管支付后应进入招募中")
                .isEqualTo("RECRUITING");
    }

    @Test
    @DisplayName("状态轨迹记录操作人类型：系统动作与人工动作可区分（追溯时很重要）")
    void status_log_records_operator_type() {
        Long taskId = draftTask("DEV", "ONLINE", "BID", "BID");
        publishService.publish(taskId, publisherId);          // 发单方
        auditService.approve(taskId, 1L, "通过");             // 审核员（系统/人工）

        List<String> types = jdbc.queryForList(
                "SELECT operator_type FROM task_status_log WHERE task_id = ? ORDER BY id", String.class, taskId);
        assertThat(types).hasSize(2);
        assertThat(types.get(0)).isEqualTo("PUBLISHER");
    }

    @Test
    @DisplayName("任务不存在时给出明确错误，而不是 NPE")
    void missing_task_gives_clear_error() {
        assertThatThrownBy(() -> publishService.publish(-1L, publisherId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TASK_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("状态机与库中状态一致：终态任务无法再走任何迁移")
    void terminal_task_cannot_transition() {
        Long taskId = draftTask("DEV", "ONLINE", "BID", "BID");
        // 直接把它置为终态（模拟已取消），再尝试发布
        jdbc.update("UPDATE task SET status = ? WHERE id = ?", TaskStatus.CANCELLED.name(), taskId);

        assertThatThrownBy(() -> publishService.publish(taskId, publisherId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TASK_STATUS_INVALID.getCode());
    }
}
