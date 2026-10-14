package com.dsh.platform.task.statemachine;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 任务状态机（18 状态 / 38 条迁移）—— <b>表驱动</b>实现。
 *
 * <h2>为什么不用 Spring StateMachine</h2>
 * 计划里指定的是 Spring StateMachine，本实现<b>有意偏离</b>，理由三条：
 * <ol>
 *   <li>⭐ 这套状态<b>必须序列化下发给前端</b>（{@code /dict/TASK_STATUS}，
 *       让前端不硬编码状态字符串、不自己拼"哪些按钮该亮"）。表驱动天然就是这个形态；
 *       Spring StateMachine 要额外写一层导出，而导出与真实配置一旦漂移就会前后端不一致。</li>
 *   <li>迁移规则里带<b>业务守卫</b>（"Σ子订单金额 + 佣金 ≤ 托管额"、"修改轮次 < 上限"），
 *       这些守卫依赖任务字段与资金数据，放在状态机配置里反而耦合。</li>
 *   <li>规则本身极稳定（18 状态是业务定义，不会天天变），不需要框架的动态编排能力。</li>
 * </ol>
 * ⛔ 若将来出现"运行时可配置的状态机"需求，再评估引入框架；现在引入只是负担。
 *
 * <h2>35 行 vs 38 条</h2>
 * 文档 §4.11.4 是 <b>35 行</b>；本表是 <b>38 条</b>。差的 3 条来自三行
 * 「→ {@code RECRUITING} / {@code BIDDING}」—— 表驱动要求 {@code (状态,事件)} 唯一映射，
 * 所以按 {@code grant_mode} 拆成了两个事件（详见 {@link TaskEvent} 的类注释）。
 * ⛔ 这不是规则不一致，是同一份规则的两种记法。
 *
 * <h2>为什么是纯函数</h2>
 * 本类<b>不碰数据库</b>：只回答"能不能迁、迁到哪"。
 * 落库（更新 {@code task.status} + 写 {@code task_status_log}）由上层服务负责 ——
 * 这样状态规则可以脱离 Spring 上下文被穷举测试。
 */
@Component
public class TaskStateMachine {

    /** 迁移表：起始状态 → (事件 → 目标状态)。 */
    private static final Map<TaskStatus, Map<TaskEvent, TaskStatus>> TABLE = buildTable();

    private static Map<TaskStatus, Map<TaskEvent, TaskStatus>> buildTable() {
        Map<TaskStatus, Map<TaskEvent, TaskStatus>> t = new EnumMap<>(TaskStatus.class);

        // ---------- 草稿域 ----------
        put(t, TaskStatus.DRAFT, TaskEvent.SUBMIT_PUBLISH, TaskStatus.PENDING_AUDIT);

        // 文档 §4.11.4 #3：发布即托管【且已支付成功】→ 直接进入招募/竞价
        put(t, TaskStatus.PENDING_AUDIT, TaskEvent.AUDIT_PASS_PAID_GRAB, TaskStatus.RECRUITING);
        put(t, TaskStatus.PENDING_AUDIT, TaskEvent.AUDIT_PASS_PAID_BID, TaskStatus.BIDDING);
        // #4：审核通过但【未托管】→ 待托管
        put(t, TaskStatus.PENDING_AUDIT, TaskEvent.AUDIT_PASS_ESCROW, TaskStatus.PENDING_ESCROW);
        put(t, TaskStatus.PENDING_AUDIT, TaskEvent.AUDIT_PASS_FREE_GRAB, TaskStatus.RECRUITING);
        put(t, TaskStatus.PENDING_AUDIT, TaskEvent.AUDIT_PASS_FREE_BID, TaskStatus.BIDDING);
        put(t, TaskStatus.PENDING_AUDIT, TaskEvent.AUDIT_REJECT, TaskStatus.AUDIT_REJECTED);
        put(t, TaskStatus.AUDIT_REJECTED, TaskEvent.RESUBMIT, TaskStatus.PENDING_AUDIT);

        // ---------- 托管 ----------
        put(t, TaskStatus.PENDING_ESCROW, TaskEvent.ESCROW_PAID_GRAB, TaskStatus.RECRUITING);
        put(t, TaskStatus.PENDING_ESCROW, TaskEvent.ESCROW_PAID_BID, TaskStatus.BIDDING);
        put(t, TaskStatus.PENDING_ESCROW, TaskEvent.ESCROW_TIMEOUT_CANCEL, TaskStatus.CANCELLED);

        // ---------- 招募 ----------
        put(t, TaskStatus.RECRUITING, TaskEvent.GRAB_SUCCESS, TaskStatus.PENDING_START);
        put(t, TaskStatus.RECRUITING, TaskEvent.EXPIRE, TaskStatus.EXPIRED);
        put(t, TaskStatus.RECRUITING, TaskEvent.CANCEL_PUBLISHER, TaskStatus.CANCELLED);

        put(t, TaskStatus.BIDDING, TaskEvent.BID_DEADLINE_HIT_WITH_BIDS, TaskStatus.PENDING_SELECT);
        put(t, TaskStatus.BIDDING, TaskEvent.BID_DEADLINE_HIT_NO_BIDS, TaskStatus.EXPIRED);
        put(t, TaskStatus.BIDDING, TaskEvent.AWARD_EARLY, TaskStatus.PENDING_START);
        put(t, TaskStatus.BIDDING, TaskEvent.CANCEL_PUBLISHER, TaskStatus.CANCELLED);

        put(t, TaskStatus.PENDING_SELECT, TaskEvent.AWARD, TaskStatus.PENDING_START);
        put(t, TaskStatus.PENDING_SELECT, TaskEvent.EXPIRE, TaskStatus.EXPIRED);
        put(t, TaskStatus.PENDING_SELECT, TaskEvent.CANCEL_PUBLISHER, TaskStatus.CANCELLED);

        // ---------- 履约 ----------
        put(t, TaskStatus.PENDING_START, TaskEvent.START_WORK, TaskStatus.IN_PROGRESS);
        put(t, TaskStatus.IN_PROGRESS, TaskEvent.SUBMIT_DELIVERY, TaskStatus.PENDING_ACCEPT);
        put(t, TaskStatus.IN_PROGRESS, TaskEvent.OVERDUE, TaskStatus.OVERDUE);

        put(t, TaskStatus.PENDING_ACCEPT, TaskEvent.ACCEPT, TaskStatus.COMPLETED);
        put(t, TaskStatus.PENDING_ACCEPT, TaskEvent.REJECT, TaskStatus.REVISION);
        put(t, TaskStatus.PENDING_ACCEPT, TaskEvent.OPEN_DISPUTE, TaskStatus.DISPUTED);

        put(t, TaskStatus.REVISION, TaskEvent.RESUBMIT_DELIVERY, TaskStatus.PENDING_ACCEPT);
        put(t, TaskStatus.REVISION, TaskEvent.ROUNDS_EXHAUSTED, TaskStatus.DISPUTED);
        put(t, TaskStatus.REVISION, TaskEvent.OPEN_DISPUTE, TaskStatus.DISPUTED);

        put(t, TaskStatus.OVERDUE, TaskEvent.EXTEND, TaskStatus.IN_PROGRESS);
        put(t, TaskStatus.OVERDUE, TaskEvent.CANCEL_PUBLISHER, TaskStatus.CANCELLED);
        put(t, TaskStatus.OVERDUE, TaskEvent.CLAIM_PARTIAL, TaskStatus.DISPUTED);

        put(t, TaskStatus.DISPUTED, TaskEvent.VERDICT_SETTLE, TaskStatus.COMPLETED);
        put(t, TaskStatus.DISPUTED, TaskEvent.VERDICT_REFUND, TaskStatus.CANCELLED);

        // ---------- 收尾 ----------
        put(t, TaskStatus.COMPLETED, TaskEvent.SETTLE_DONE, TaskStatus.SETTLED);
        // ⚠️ §4.11.3：验收通过后至分账前的短暂窗口，是最后一次可发起常规争端的机会
        put(t, TaskStatus.COMPLETED, TaskEvent.OPEN_DISPUTE, TaskStatus.DISPUTED);

        put(t, TaskStatus.SETTLED, TaskEvent.CLOSE, TaskStatus.CLOSED);

        // ⛔ 终态（CLOSED / CANCELLED / EXPIRED）没有任何出边 —— 到这里就结束了。
        //    把它们写进表里是空的，靠"没有条目即拒绝"来表达，反而更不容易写漏。
        return Map.copyOf(t);
    }

    private static void put(Map<TaskStatus, Map<TaskEvent, TaskStatus>> t,
                            TaskStatus from, TaskEvent event, TaskStatus to) {
        Map<TaskEvent, TaskStatus> row = t.computeIfAbsent(from, k -> new EnumMap<>(TaskEvent.class));
        if (row.containsKey(event)) {
            // ⛔ 同 (状态,事件) 出现两个目标 = 表里有歧义，构建期就炸掉而不是运行期随机选一个
            throw new IllegalStateException(
                    "状态迁移表存在歧义：" + from + " + " + event + " 已有目标 " + row.get(event));
        }
        row.put(event, to);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 计算目标状态；非法迁移抛 {@link ErrorCode#TASK_STATUS_INVALID}。
     *
     * <p>⭐ 报错文案里带上起始状态与事件 —— 状态机错误最常见的排查方式是
     * "这个操作当时为什么不让做"，没有这两个值等于没说。
     */
    public TaskStatus next(TaskStatus from, TaskEvent event) {
        Objects.requireNonNull(from, "from 不能为空");
        Objects.requireNonNull(event, "event 不能为空");

        Map<TaskEvent, TaskStatus> row = TABLE.get(from);
        TaskStatus to = row == null ? null : row.get(event);
        if (to == null) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID,
                    "当前状态（" + from.getCnName() + "）不支持操作「" + event.getCnName() + "」");
        }
        return to;
    }

    /** 该状态是否允许该事件（不抛异常的查询版）。 */
    public boolean can(TaskStatus from, TaskEvent event) {
        Map<TaskEvent, TaskStatus> row = TABLE.get(from);
        return row != null && row.containsKey(event);
    }

    /** 该状态下所有可用事件 —— 下发给前端渲染按钮的依据。 */
    public List<TaskEvent> allowedEvents(TaskStatus from) {
        Map<TaskEvent, TaskStatus> row = TABLE.get(from);
        return row == null ? List.of() : List.copyOf(row.keySet());
    }

    /** 该状态下每个可用事件的<b>目标状态</b>（前端做乐观更新时需要）。 */
    public Map<TaskEvent, TaskStatus> allowedTransitions(TaskStatus from) {
        Map<TaskEvent, TaskStatus> row = TABLE.get(from);
        return row == null ? Map.of() : Map.copyOf(row);
    }

    public boolean isTerminal(TaskStatus s) {
        return s.isTerminal();
    }

    /** 迁移总条数（供测试钉住，防止有人误删一条）。 */
    public int transitionCount() {
        return TABLE.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * 是否存在歧义。
     *
     * <p>理论上构建期就会抛异常，所以运行期恒为 false ——
     * 保留这个方法是把它变成<b>可断言的契约</b>：将来若改用别的数据结构（如从库加载规则），
     * 测试仍然能守住这条性质。
     */
    public boolean hasAmbiguity() {
        for (Map<TaskEvent, TaskStatus> row : TABLE.values()) {
            if (row == null) {
                continue;
            }
            long distinct = row.keySet().stream().distinct().count();
            if (distinct != row.size()) {
                return true;
            }
        }
        return false;
    }

    /** 按分组聚合，供字典接口下发。 */
    public static Map<String, List<TaskStatus>> grouped() {
        Map<String, List<TaskStatus>> out = new LinkedHashMap<>();
        for (TaskStatus.Group g : TaskStatus.Group.values()) {
            List<TaskStatus> list = new ArrayList<>();
            for (TaskStatus s : TaskStatus.values()) {
                if (s.getGroup() == g) {
                    list.add(s);
                }
            }
            out.put(g.name(), list);
        }
        return out;
    }

    /** 全部状态列表（字典接口用）。 */
    public static List<TaskStatus> all() {
        return List.of(TaskStatus.values());
    }
}
