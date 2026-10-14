package com.dsh.platform.task.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 抢单结果。
 *
 * <p>⭐ 失败时<b>必须</b>带上 {@code failCode} 与 {@code failReason}，
 * 且文案要能指导下一步动作：
 * <ul>
 *   <li>被抢走 → 给 {@code similarTasks}，让用户立刻能接别的单，而不是反复重试同一单；</li>
 *   <li>不符合条件 → 说清差在哪（差几分信誉分、已有几个进行中任务）。</li>
 * </ul>
 * ⛔ 不要用"系统繁忙"这类通用文案打发 —— 抢单是移动端最高频操作，
 * 失败率与失败原因直接决定用户还愿不愿意用。
 */
@Data
@Builder
public class GrabResult {

    private boolean success;

    /** 成功时返回生成的子订单 id。 */
    private Long subOrderId;

    /** 失败错误码。 */
    private Integer failCode;

    /** 失败原因（用户可读）。 */
    private String failReason;

    /** 失败时的相似任务推荐（按分类 + 预算区间取若干条）。 */
    private List<Long> similarTasks;

    public static GrabResult ok(Long subOrderId) {
        return GrabResult.builder().success(true).subOrderId(subOrderId).build();
    }

    public static GrabResult fail(int code, String reason) {
        return GrabResult.builder().success(false).failCode(code).failReason(reason).build();
    }
}