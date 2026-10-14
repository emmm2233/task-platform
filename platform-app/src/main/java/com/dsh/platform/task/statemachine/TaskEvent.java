package com.dsh.platform.task.statemachine;

import lombok.Getter;

/**
 * 任务状态机的触发事件。
 *
 * <p>⭐ <b>为什么同一个语义在有些地方要拆成两个事件</b>：
 * 表驱动状态机要求 {@code (状态, 事件) → 唯一目标状态}。而文档里若干行写的是
 * 「→ `RECRUITING` / `BIDDING`」—— 到底去哪个由 {@code grant_mode}（抢单/竞拍）决定。
 *
 * <p>两种处理方式：
 * <ol>
 *   <li>让 {@code next()} 多收一个 grantMode 参数 → 破坏"无歧义"这个可测试性质；</li>
 *   <li>⭐ <b>拆成两个事件</b>（本实现）：{@code ESCROW_PAID_GRAB} / {@code ESCROW_PAID_BID}，
 *       由调用方（T11 的发布服务）读 {@code task.grant_mode} 后选择投递哪个事件。</li>
 * </ol>
 * 选 2 的理由：状态机保持纯函数与无歧义，而"按 grant_mode 选事件"本就是业务侧的判断，
 * 放在状态机里反而会把它和任务字段耦合起来。
 *
 * <p>因此本表共 <b>38 条</b>迁移，而文档 §4.11.4 是 <b>35 行</b> —— 差的 3 条正是
 * 「RECRUITING / BIDDING」那三行各拆成了两个事件。⛔ 这不是不一致，是同一份规则的两种记法。
 */
@Getter
public enum TaskEvent {

    // ---- 草稿域 ----
    /** 提交发布：DRAFT → PENDING_AUDIT。 */
    SUBMIT_PUBLISH("提交发布"),
    /** 审核：发布即托管【且已支付成功】+ 抢单 → 招募中。 */
    AUDIT_PASS_PAID_GRAB("审核通过（发布即托管·已支付·抢单）"),
    /** 审核：发布即托管【且已支付成功】+ 竞拍 → 竞价中。 */
    AUDIT_PASS_PAID_BID("审核通过（发布即托管·已支付·竞拍）"),
    /** 审核：发布即托管但【未支付】→ 待托管。 */
    AUDIT_PASS_ESCROW("审核通过（待托管）"),
    /** 审核：免托管 + 抢单 → 招募中。 */
    AUDIT_PASS_FREE_GRAB("审核通过（免托管·抢单）"),
    /** 审核：免托管 + 竞拍 → 竞价中。 */
    AUDIT_PASS_FREE_BID("审核通过（免托管·竞拍）"),
    /** 人工审核驳回（必填原因）。 */
    AUDIT_REJECT("审核驳回"),
    /** 修改后重新提交。 */
    RESUBMIT("重新提交"),

    // ---- 托管 ----
    /** 托管支付成功（抢单模式）→ 招募中。 */
    ESCROW_PAID_GRAB("托管支付成功·抢单"),
    /** 托管支付成功（竞拍模式）→ 竞价中。 */
    ESCROW_PAID_BID("托管支付成功·竞拍"),
    /** 超时未支付（30 分钟）→ 已取消。 */
    ESCROW_TIMEOUT_CANCEL("托管超时未支付"),

    // ---- 招募 ----
    /** ⭐ 抢单成功。唯一性由条件 UPDATE 的影响行数保证（§5.5.1）。 */
    GRAB_SUCCESS("抢单成功"),
    /** 报价截止且有报价 → 待选标。 */
    BID_DEADLINE_HIT_WITH_BIDS("报价截止·有报价"),
    /** 报价截止且无报价 → 已流标。 */
    BID_DEADLINE_HIT_NO_BIDS("报价截止·无报价"),
    /** 发单方提前选定。 */
    AWARD_EARLY("提前选定中标方"),
    /** 待选标 → 选定中标方 + 托管完成。 */
    AWARD("选定中标方"),
    /** 超时未选定 / 截止无人抢 → 已流标。 */
    EXPIRE("超时流标"),
    /** 发单方取消（未中标可免费取消）。 */
    CANCEL_PUBLISHER("发单方取消"),

    // ---- 履约 ----
    /** 到达开始时间 或 接单方点击开始。 */
    START_WORK("开始履约"),
    /** 提交交付物。 */
    SUBMIT_DELIVERY("提交交付物"),
    /** 超过交付截止。 */
    OVERDUE("超期未交付"),
    /** 验收通过（含超时自动验收）。 */
    ACCEPT("验收通过"),
    /** 驳回（理由必填、修改轮次未用尽）。 */
    REJECT("验收驳回"),
    /** 返工后重新交付。 */
    RESUBMIT_DELIVERY("重新交付"),
    /** 修改轮次用尽 → 转争端。 */
    ROUNDS_EXHAUSTED("修改轮次用尽"),
    /** 双方同意延期（发单方确认新截止时间）。 */
    EXTEND("同意延期"),
    /** 接单方主张部分完成 → 转争端。 */
    CLAIM_PARTIAL("主张部分完成"),

    // ---- 争端 ----
    /** ⚠️ 只能在结算前发起（§4.11.3）。SETTLED 之后不可。 */
    OPEN_DISPUTE("发起争端"),
    /** 裁决：结算 / 部分结算。 */
    VERDICT_SETTLE("裁决·结算"),
    /** 裁决：全额退款。 */
    VERDICT_REFUND("裁决·退款"),

    // ---- 收尾 ----
    /** 全部分额已分账。 */
    SETTLE_DONE("分账完成"),
    /** 评价完成 / 评价期结束。 */
    CLOSE("归档关闭");

    private final String cnName;

    TaskEvent(String cnName) {
        this.cnName = cnName;
    }
}
