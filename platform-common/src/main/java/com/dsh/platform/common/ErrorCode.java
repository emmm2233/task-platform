package com.dsh.platform.common;

import lombok.Getter;

/**
 * 业务错误码。
 *
 * <p>分段口径（与设计文档 §5.7.3 一致，⛔ 不得随意插号）：
 * <pre>
 *   0        成功
 *   10xxx    通用 / 参数
 *   20xxx    认证授权
 *   30xxx    任务
 *   31xxx    抢单 / 竞价
 *   32xxx    订单 / 交付
 *   40xxx    资金
 *   41xxx    抽成
 *   50xxx    争端
 *   60xxx    平台治理
 *   70xxx    信誉分
 *   90xxx    外部渠道
 * </pre>
 *
 * <p>原则：<b>能被用户看懂并据此采取行动的，才配拥有独立错误码</b>。
 * 抢单失败必须区分「被抢走」（去看别的任务）与「你不符合条件」（去完善资质），
 * 这两条路径完全不同。
 */
@Getter
public enum ErrorCode {

    OK(0, "OK"),

    // ---------- 10xxx 通用 / 参数 ----------
    PARAM_INVALID(10001, "参数校验失败"),
    RATE_LIMITED(10002, "请求过于频繁，请稍后再试"),
    VERSION_TOO_LOW(10998, "客户端版本过低，请升级后使用"),
    SYSTEM_BUSY(10999, "系统繁忙，请稍后再试"),

    // ---------- 20xxx 认证授权 ----------
    NOT_LOGIN(20001, "未登录或登录已失效"),
    TOKEN_EXPIRED(20002, "登录已过期，请重新登录"),
    NO_PERMISSION(20003, "无权限执行该操作"),
    ACCOUNT_FROZEN(20004, "账号已被冻结"),
    NOT_REAL_NAME(20005, "请先完成实名认证"),
    ACCOUNT_LOCKED(20006, "账号已锁定，请稍后再试"),

    // ---------- 30xxx 任务 ----------
    TASK_NOT_FOUND(30001, "任务不存在"),
    TASK_STATUS_INVALID(30002, "当前任务状态不允许该操作"),
    NOT_TASK_OWNER(30003, "你不是该任务的发布者"),
    TASK_AUDIT_REJECTED(30004, "任务审核未通过"),

    // ---------- 31xxx 抢单 / 竞价 ----------
    GRAB_BUSY(31001, "该任务正在被抢，请重试"),
    GRAB_TAKEN(31002, "该任务已被他人抢走"),
    BID_CLOSED(31003, "报价已截止"),
    BID_OUT_OF_RANGE(31004, "报价超出允许范围"),
    AWARD_EXCEED_ESCROW(31005, "中标金额超出托管金额"),
    SINGLE_MODE_ONE_BID(31006, "单选模式只能选定一个接单方"),

    // ---------- 32xxx 订单 / 交付 ----------
    SUB_ORDER_STATUS_INVALID(32001, "当前订单状态不允许该操作"),
    DELIVERY_OVERDUE(32002, "已超过交付截止时间"),
    REJECT_REASON_REQUIRED(32003, "驳回必须说明原因"),
    MODIFY_ROUND_EXHAUSTED(32004, "修改轮次已用尽，已转人工处理"),

    // ---------- 40xxx 资金 ----------
    BALANCE_INSUFFICIENT(40001, "余额不足"),
    ESCROW_CREATE_FAILED(40002, "托管创建失败"),
    DUPLICATE_REQUEST(40003, "重复请求，已返回首次结果"),
    SETTLE_FAILED(40004, "分账失败"),
    WITHDRAW_AUDITING(40005, "提现正在审核中"),
    BANK_NAME_MISMATCH(40006, "收款人姓名需与实名认证一致"),

    // ---------- 41xxx 抽成 ----------
    COMMISSION_RULE_NOT_FOUND(41001, "未命中任何抽成费率规则"),
    COMMISSION_NOT_BALANCED(41002, "佣金与实得不配平"),
    COMMISSION_RATE_OUT_OF_RANGE(41003, "费率超出允许范围（1%–20%）"),
    COMMISSION_TIER_OVERLAP(41004, "金额阶梯区间重叠"),
    COMMISSION_ACTIVITY_OVERLAP(41005, "活动时间交叉"),
    COMMISSION_DETAIL_EXISTS(41006, "抽成明细已存在"),
    COMMISSION_SETTLED_CANNOT_RECALC(41007, "已分账不可重算"),

    // ---------- 50xxx 争端 ----------
    TICKET_NOT_FOUND(50001, "工单不存在"),
    EVIDENCE_PERIOD_CLOSED(50002, "举证期已结束"),
    VERDICT_EXCEED_LIMIT(50003, "裁决金额超出你的权限上限，需上级复核"),
    VERDICT_ALREADY_ISSUED(50004, "该工单已裁决，不可重复裁决"),
    APPEAL_USED(50005, "申诉机会已用完"),

    // ---------- 60xxx 平台治理 ----------
    NO_ADMIN_PERMISSION(60001, "无管理权限"),
    INTERNAL_ACCOUNT_FORBIDDEN(60002, "内部账号不可执行该操作"),
    CONFIG_INVALID(60003, "参数配置非法"),
    ROLE_CONFLICT(60004, "岗位互斥，不可同时分配"),
    APPROVAL_NOT_COMPLETED(60005, "审批未完成，动作已被拦截"),
    APPROVAL_SELF_VOTE(60006, "发起人不得为自己的动作投票"),

    // ---------- 70xxx 信誉分 ----------
    CREDIT_OUT_OF_RANGE(70001, "信誉分超出有效区间（0–100）"),
    CREDIT_EVENT_APPLIED(70002, "该事件已计分"),
    CREDIT_DAILY_CAP_REACHED(70003, "今日加分已达上限"),
    CREDIT_INSUFFICIENT(70004, "信誉分不足，暂时无法接单"),
    ONGOING_TASK_LIMIT(70005, "进行中任务数已达当前等级上限"),
    CREDIT_APPEAL_EXPIRED(70006, "扣分申诉已过期或已使用"),
    CREDIT_ADJUST_EXCEED_LIMIT(70007, "单次调整超过上限（20 分），需超管复核"),

    // ---------- 90xxx 外部渠道 ----------
    CHANNEL_TIMEOUT(90001, "支付渠道超时，请稍后重试"),
    CHANNEL_SIGN_INVALID(90002, "渠道验签失败"),
    CHANNEL_ERROR(90003, "支付渠道返回异常"),

    // ---------- 资金段 ----------
    /** 支付金额与应付金额不一致（少付后面不够分，多付要走退款分支）。 */
    AMOUNT_MISMATCH(40007, "支付金额与应付金额不一致"),
    ;

    /** 业务码。 */
    private final int code;

    /** 默认的用户可读文案（不含堆栈与技术细节）。 */
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
