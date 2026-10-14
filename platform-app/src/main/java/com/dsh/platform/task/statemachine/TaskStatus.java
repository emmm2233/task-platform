package com.dsh.platform.task.statemachine;

import lombok.Getter;

/**
 * 任务状态全集（18 个）。
 *
 * <p>来源：设计文档 §4.11.2。⛔ 不要凭记忆增删 —— 状态数与迁移表是被测试钉住的。
 *
 * <p>⭐ 每个状态带<b>分组</b>与<b>中文名</b>，是为了让前端不必硬编码状态字符串：
 * 界面按分组配色、按中文名展示，新增状态时前端不用改代码。
 *
 * <p>⚠️ 与 DDL 注释的出入：{@code task.status} 的列注释写着"由 {@code sys_config} 维护"，
 * 但 {@code sys_config} 里并没有状态字典、库里也没有字典表 —— 真实定义在文档 §4.11.2，
 * 由本枚举承载。列注释那句话是不准确的（已记录，需要一次迁移来订正注释）。
 */
@Getter
public enum TaskStatus {

    // ---- 草稿域：还没对外发生 ----
    DRAFT("草稿", Group.DRAFT, false),
    PENDING_AUDIT("待审核", Group.DRAFT, false),
    AUDIT_REJECTED("审核未通过", Group.DRAFT, false),

    // ---- 招募域：正在找接单方 ----
    PENDING_ESCROW("待托管", Group.RECRUITING, false),
    RECRUITING("招募中", Group.RECRUITING, false),
    BIDDING("竞价中", Group.RECRUITING, false),
    PENDING_SELECT("待选标", Group.RECRUITING, false),

    // ---- 履约域：已中标，正在干活 ----
    PENDING_START("待开始", Group.FULFILLING, false),
    IN_PROGRESS("履约中", Group.FULFILLING, false),
    PENDING_ACCEPT("待验收", Group.FULFILLING, false),
    REVISION("返工中", Group.FULFILLING, false),
    OVERDUE("已逾期", Group.FULFILLING, false),
    DISPUTED("争议中", Group.FULFILLING, false),

    // ---- 收尾域：活干完了，钱还没分完 ----
    COMPLETED("已完成", Group.SETTLING, false),
    SETTLED("已结算", Group.SETTLING, false),

    // ---- 终态：出不去 ----
    CLOSED("已关闭", Group.TERMINAL, true),
    CANCELLED("已取消", Group.TERMINAL, true),
    EXPIRED("已流标", Group.TERMINAL, true);

    private final String cnName;

    private final Group group;

    /** 是否终态（终态无出边）。 */
    private final boolean terminal;

    TaskStatus(String cnName, Group group, boolean terminal) {
        this.cnName = cnName;
        this.group = group;
        this.terminal = terminal;
    }

    /**
     * 状态分组（5 个）。
     *
     * <p>用途：列表页按分组着色与折叠；也用于"能不能发起争端"这类<b>按组</b>的规则
     * （§4.11.3：履约域 + COMPLETED 可发起，SETTLED 及之后不可）。
     */
    public enum Group {
        DRAFT("草稿域"),
        RECRUITING("招募域"),
        FULFILLING("履约域"),
        SETTLING("收尾域"),
        TERMINAL("终态");

        @Getter
        private final String cnName;

        Group(String cnName) {
            this.cnName = cnName;
        }
    }
}
