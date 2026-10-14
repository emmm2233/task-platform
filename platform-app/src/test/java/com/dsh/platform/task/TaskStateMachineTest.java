package com.dsh.platform.task;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.task.statemachine.TaskEvent;
import com.dsh.platform.task.statemachine.TaskStateMachine;
import com.dsh.platform.task.statemachine.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T10 · 任务状态机（18 状态 / 35 条迁移）。
 *
 * <p>本测试是<b>纯表驱动</b>的：状态机不碰数据库，
 * 校验"给定起始状态与事件，是否允许迁移、迁到哪"。
 * 落库（写 {@code task_status_log}）由上层服务负责 —— T11 驱动真实流转时接上。
 *
 * <p>⭐ 为什么要显式测"非法迁移"：状态机的价值<b>不在于允许什么，而在于拒绝什么</b>。
 * 一个只会放行的状态机等于没有状态机 —— 而未审核就履约、未中标就完成、
 * 已分账还去退款，每一条都是资损或纠纷。
 */
class TaskStateMachineTest {

    private final TaskStateMachine sm = new TaskStateMachine();

    // ------------------------------------------------------------------
    // ① 18 个状态与 5 个分组
    // ------------------------------------------------------------------

    @Test
    @DisplayName("恰好 18 个状态，且每个都有分组与中文名")
    void there_are_exactly_18_statuses() {
        assertThat(TaskStatus.values()).hasSize(18);
        for (TaskStatus s : TaskStatus.values()) {
            assertThat(s.getGroup()).as("%s 必须有分组", s).isNotNull();
            assertThat(s.getGroup().getCnName()).as("%s 的分组必须有中文名", s).isNotBlank();
            assertThat(s.getCnName()).as("%s 必须有中文名", s).isNotBlank();
        }
    }

    @Test
    @DisplayName("5 个分组，覆盖全部 18 个状态")
    void statuses_are_grouped_into_5_groups() {
        Map<String, List<TaskStatus>> byGroup = TaskStateMachine.grouped();
        assertThat(byGroup.keySet())
                .containsExactlyInAnyOrder("DRAFT", "RECRUITING", "FULFILLING", "SETTLING", "TERMINAL");
        assertThat(byGroup.values().stream().mapToInt(List::size).sum()).isEqualTo(18);
    }

    @ParameterizedTest
    @EnumSource(TaskStatus.class)
    @DisplayName("每个状态都有可用操作列表（前端据此渲染按钮，⛔ 不硬编码状态字符串）")
    void every_status_exposes_allowed_events(TaskStatus s) {
        assertThat(sm.allowedEvents(s)).as("%s 的可用操作", s).isNotNull();
    }

    // ------------------------------------------------------------------
    // ② 合法迁移（抽样覆盖各分组）
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "DRAFT,          SUBMIT_PUBLISH,    PENDING_AUDIT",
            "PENDING_AUDIT,  AUDIT_PASS_ESCROW, PENDING_ESCROW",
            "PENDING_AUDIT,  AUDIT_REJECT,      AUDIT_REJECTED",
            "AUDIT_REJECTED, RESUBMIT,          PENDING_AUDIT",
            "PENDING_ESCROW, ESCROW_PAID_GRAB,  RECRUITING",
            "RECRUITING,     GRAB_SUCCESS,      PENDING_START",
            "RECRUITING,     EXPIRE,            EXPIRED",
            "BIDDING,        BID_DEADLINE_HIT_WITH_BIDS, PENDING_SELECT",
            "BIDDING,        AWARD_EARLY,       PENDING_START",
            "PENDING_SELECT, AWARD,             PENDING_START",
            "PENDING_SELECT, EXPIRE,            EXPIRED",
            "PENDING_START,  START_WORK,        IN_PROGRESS",
            "IN_PROGRESS,    SUBMIT_DELIVERY,   PENDING_ACCEPT",
            "IN_PROGRESS,    OVERDUE,           OVERDUE",
            "PENDING_ACCEPT, ACCEPT,            COMPLETED",
            "PENDING_ACCEPT, REJECT,            REVISION",
            "PENDING_ACCEPT, OPEN_DISPUTE,      DISPUTED",
            "REVISION,      RESUBMIT_DELIVERY, PENDING_ACCEPT",
            "REVISION,      ROUNDS_EXHAUSTED,  DISPUTED",
            "OVERDUE,       EXTEND,            IN_PROGRESS",
            "OVERDUE,       CLAIM_PARTIAL,     DISPUTED",
            "DISPUTED,      VERDICT_SETTLE,    COMPLETED",
            "DISPUTED,      VERDICT_REFUND,    CANCELLED",
            "COMPLETED,     SETTLE_DONE,       SETTLED",
            "COMPLETED,     OPEN_DISPUTE,      DISPUTED",
            "SETTLED,       CLOSE,             CLOSED",
    })
    @DisplayName("合法迁移必须放行，且目标状态正确")
    void legal_transitions_are_allowed(TaskStatus from, TaskEvent event, TaskStatus expected) {
        assertThat(sm.next(from, event))
                .as("%s + %s", from, event)
                .isEqualTo(expected);
    }

    // ------------------------------------------------------------------
    // ③ ⭐ 非法迁移 —— 状态机的价值全在这里
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ DRAFT → IN_PROGRESS：未审核不能履约")
    void draft_cannot_start_work() {
        assertRejected(TaskStatus.DRAFT, TaskEvent.START_WORK);
    }

    @Test
    @DisplayName("⭐ RECRUITING → COMPLETED：未中标不能完成")
    void recruiting_cannot_complete() {
        assertRejected(TaskStatus.RECRUITING, TaskEvent.ACCEPT);
    }

    @Test
    @DisplayName("⭐ PENDING_ESCROW → RECRUITING：未支付不能招募（除非免托管分类，那走另一条迁移）")
    void pending_escrow_cannot_go_recruiting_without_payment() {
        assertRejected(TaskStatus.PENDING_ESCROW, TaskEvent.GRAB_SUCCESS);
        assertRejected(TaskStatus.PENDING_ESCROW, TaskEvent.BID_DEADLINE_HIT_WITH_BIDS);
        // 只有"托管成功"这一个入口
        assertThat(sm.next(TaskStatus.PENDING_ESCROW, TaskEvent.ESCROW_PAID_GRAB))
                .isEqualTo(TaskStatus.RECRUITING);
    }

    @Test
    @DisplayName("⭐ SETTLED → DISPUTED：已分账不可走常规争端（§4.11.3，从流程上消灭退款分支）")
    void settled_cannot_open_dispute() {
        assertRejected(TaskStatus.SETTLED, TaskEvent.OPEN_DISPUTE);
    }

    @Test
    @DisplayName("⭐ 终态不可复活：CLOSED / CANCELLED / EXPIRED 出不去")
    void terminal_states_are_final() {
        for (TaskStatus terminal : List.of(TaskStatus.CLOSED, TaskStatus.CANCELLED, TaskStatus.EXPIRED)) {
            assertThat(sm.isTerminal(terminal)).as("%s 应是终态", terminal).isTrue();
            for (TaskEvent e : TaskEvent.values()) {
                assertThatThrownBy(() -> sm.next(terminal, e))
                        .as("终态 %s 不应响应事件 %s", terminal, e)
                        .isInstanceOf(BizException.class);
            }
        }
    }

    @Test
    @DisplayName("⭐ COMPLETED 的争端窗口：只能到 SETTLED，且窗口内可开争端")
    void completed_has_a_short_dispute_window() {
        // 验收通过 → 结算前，是最后一次可发起争端的机会
        assertThat(sm.next(TaskStatus.COMPLETED, TaskEvent.OPEN_DISPUTE)).isEqualTo(TaskStatus.DISPUTED);
        assertThat(sm.next(TaskStatus.COMPLETED, TaskEvent.SETTLE_DONE)).isEqualTo(TaskStatus.SETTLED);
        // ⛔ 但已结算就再也回不来
        assertRejected(TaskStatus.SETTLED, TaskEvent.OPEN_DISPUTE);
    }

    @Test
    @DisplayName("拒绝时给出可读原因，而不是通用参数错误")
    void rejection_message_is_actionable() {
        assertThatThrownBy(() -> sm.next(TaskStatus.DRAFT, TaskEvent.START_WORK))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("草稿")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TASK_STATUS_INVALID.getCode());
    }

    // ------------------------------------------------------------------
    // ④ 迁移表自身的完整性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("迁移表共 38 条 —— 文档 35 行 / 37 行减去两条非状态迁移，差额来自三行双目标")
    void transition_table_size_is_pinned() {
        // 文档 §4.11.4 共 37 行，减去两条非状态迁移（#2 草稿删除、#37 举报转人工）= 35 行。
        // 但表驱动要求 (状态,事件) → 唯一目标，而其中三行写的是「→ RECRUITING / BIDDING」
        // （取决于 grant_mode），按 TaskEvent 的注释拆成两个事件后是 38 条。
        // ⛔ 不是规则不一致，是同一份规则的两种记法 —— 这个数字被钉住以防误删。
        assertThat(sm.transitionCount()).isEqualTo(38);
    }

    @Test
    @DisplayName("除终态外，每个状态都至少有一条出边（否则会出现无路可走的状态）")
    void no_dead_end_states() {
        for (TaskStatus s : TaskStatus.values()) {
            if (sm.isTerminal(s)) {
                continue;
            }
            assertThat(sm.allowedEvents(s))
                    .as("%s 不是终态，必须至少有一条出边", s)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("不允许同一 (状态, 事件) 映射到两个不同目标（表里不能有歧义）")
    void transition_table_has_no_ambiguity() {
        assertThat(sm.hasAmbiguity()).as("同 (状态,事件) 存在多个目标").isFalse();
    }

    private void assertRejected(TaskStatus from, TaskEvent event) {
        assertThatThrownBy(() -> sm.next(from, event))
                .as("%s + %s 必须被拒绝", from, event)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TASK_STATUS_INVALID.getCode());
    }
}
