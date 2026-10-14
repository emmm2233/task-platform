package com.dsh.platform.fund.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.common.db.RowValues;
import com.dsh.platform.task.statemachine.TaskEvent;
import com.dsh.platform.task.service.TaskStateMachineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 验收 → 分账：把已有能力串成一条真实链路。
 *
 * <p>⭐ <b>本类的价值在于"接通"而不是"新增"</b>。到这里为止各部分都已单独验证过：
 * 状态机能判定 {@code PENDING_ACCEPT + ACCEPT → COMPLETED}（T10）、
 * 托管与账本已能收钱入池（T16）、分账编排已能按份额释放（T17）——
 * 但 <b>{@link SettlementService} 一个调用方都没有</b>。
 * 一个功能齐全却没人调用的服务，等于没做。
 *
 * <h2>为什么整条链路必须在同一个事务里</h2>
 * ⛔ <b>"状态改了、分账失败了"是最坏的状态</b>：用户看到"已完成"，钱却还在托管池。
 * 那既要人工介入退款，又无法从状态判断该不该重放。所以这里要么全成、要么全不动。
 *
 * <h2>份额是怎么来的</h2>
 * Phase 1 一个任务一个 {@code WHOLE} 结算单元（份额 = 托管额），
 * 所以验收时按需补齐 {@code sub_order → delivery_unit → escrow_share} 三级单据。
 * ⚠️ 真实链路里 {@code sub_order} 应在指派（抢单/中标）时就建；
 * 这里"按需补齐"是为了让验收端到端可用，等履约域落齐后应把创建点前移。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcceptanceService {

    private final TaskStateMachineService sm;
    private final SettlementService settlementService;
    private final SettlementDocumentService docs;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 验收通过并发起分账。
     *
     * @param actorId 发单方或接单方（⛔ 第三方不可替人验收）
     */
    @Transactional
    public void accept(Long taskId, Long actorId, String remark) {
        // ① 锁任务行
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT publisher_id, assignee_id, status, escrow_amount
                  FROM task WHERE id = ? FOR UPDATE
                """, taskId);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        Map<String, Object> t = rows.get(0);

        boolean isPublisher = String.valueOf(t.get("publisher_id")).equals(String.valueOf(actorId));
        boolean isTaker = t.get("assignee_id") != null
                && String.valueOf(t.get("assignee_id")).equals(String.valueOf(actorId));
        if (!isPublisher && !isTaker) {
            throw new BizException(ErrorCode.NOT_TASK_OWNER, "只有发单方或接单方可以确认验收");
        }

        // ② 状态机推进（状态不对会在这里抛，且事务回滚 → 钱一分不动）
        sm.fire(taskId, TaskEvent.ACCEPT, actorId,
                isPublisher ? TaskStateMachineService.OP_PUBLISHER : TaskStateMachineService.OP_TAKER,
                remark);

        // ③ 分账：补齐份额并释放
        settleIfEscrowed(taskId, (BigDecimal) t.get("escrow_amount"));
    }

    /**
     * 有托管就释放，没有就跳过。
     *
     * <p>⚠️ 免托管任务（低风险分类）没有 escrow，验收后不该报错 ——
     * 所以这里按"有无托管"分支，而不是要求托管必须存在。
     */
    private void settleIfEscrowed(Long taskId, BigDecimal escrowAmount) {
        List<Map<String, Object>> e = jdbc.queryForList("""
                SELECT e.id AS escrow_id, e.order_id, e.amount
                  FROM escrow e JOIN biz_order o ON o.id = e.order_id
                 WHERE o.task_id = ?
                 ORDER BY e.id DESC LIMIT 1
                """, taskId);
        if (e.isEmpty()) {
            log.info("任务 {} 无托管单，跳过分账（免托管任务）", taskId);
            return;
        }
        Long escrowId = ((Number) e.get(0).get("escrow_id")).longValue();
        Long orderId = ((Number) e.get(0).get("order_id")).longValue();
        BigDecimal amount = (BigDecimal) e.get(0).get("amount");

        Long takerId = jdbc.queryForObject("SELECT assignee_id FROM task WHERE id = ?", Long.class, taskId);
        // ⭐ 单据补齐已抽到 SettlementDocumentService（原先两处各有一份实现）
        docs.ensureWholeShare(taskId, orderId, escrowId, takerId, amount);

        settlementService.settle(orderId, escrowId);
    }

}
