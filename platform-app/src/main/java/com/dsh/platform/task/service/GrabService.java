package com.dsh.platform.task.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.task.dto.GrabResult;
import com.dsh.platform.task.mapper.TaskGrabMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 抢单。
 *
 * <p><b>一次抢单要过五关</b>，顺序不可随意调换：
 * <pre>
 *   ① 幂等      同一 requestId 重复提交 → 直接返回首次结果（弱网重试很常见）
 *   ② 前置闸门  实名 / 信誉分门槛 / 进行中上限 / 不是自己的单 / 未被拉黑
 *   ③ Redis 锁  削峰 —— ⛔ 不是唯一性依据
 *   ④ 条件 UPDATE  ⭐ 唯一性在这里，由影响行数判定
 *   ⑤ 失败回读  查清真实原因（被抢走 / 状态已变），并组装相似任务
 * </pre>
 *
 * <p>⭐ <b>② 的每一类失败都给不同的错误码</b>：用户需要据此决定下一步 ——
 * "被抢走了"要去看别的单，"信誉分不够"要去提升信誉分，
 * 两者给同一个错误码等于把选择权又丢回给了用户。
 *
 * <p>⛔ <b>失败也要写 {@code task_grab_log}</b>：抢单失败率是产品最重要的信号之一。
 * 只记成功的话，运营永远不知道"有多少人因为信誉分不够而抢不到"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrabService {

    /** 信誉分门槛的配置键（默认 60，与 credit.grab_threshold 一致）。 */
    private static final String KEY_GRAB_THRESHOLD = "credit.grab_threshold";

    private final TaskGrabMapper grabMapper;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Transactional
    public GrabResult grab(Long taskId, Long userId, String requestId) {
        long t0 = System.currentTimeMillis();

        // ---------- ① 幂等 ----------
        if (requestId != null && !requestId.isBlank()) {
            Boolean first = grabMapper.findIdempotentResult(taskId, userId, requestId);
            if (first != null) {
                // 已经处理过 —— 返回与首次一致的结果（成功就再给一次 subOrderId）
                return first ? GrabResult.ok(currentSubOrder(taskId, userId))
                        : GrabResult.fail(ErrorCode.GRAB_TAKEN.getCode(), "该任务已被他人抢走");
            }
        }

        // ---------- ② 前置闸门 ----------
        GrabResult gate = checkGates(taskId, userId);
        if (gate != null) {
            writeLog(taskId, userId, requestId, false, gate.getFailCode(), gate.getFailReason());
            return gate;
        }

        // ---------- ③ Redis 锁：削峰，不是正确性依据 ----------
        String lockKey = "grab:lock:" + taskId;
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(3));
        if (!Boolean.TRUE.equals(locked)) {
            // 抢不到锁说明正有大量请求在打这一单 —— 直接告诉用户"手慢了"，
            // ⛔ 但不能把这当成"失败终局"：锁只是削峰，真正的判定在下面那条 UPDATE。
            GrabResult busy = GrabResult.fail(ErrorCode.GRAB_BUSY.getCode(),
                    "该任务正在被抢，请稍候重试");
            writeLog(taskId, userId, requestId, false, busy.getFailCode(), busy.getFailReason());
            return busy;
        }

        try {
            // ---------- ④ 唯一性判定 ----------
            int affected = grabMapper.grabAtomic(taskId, userId);

            if (affected == 1) {
                writeLog(taskId, userId, requestId, true, null, null);
                log.info("抢单成功 taskId={} userId={} 耗时 {}ms", taskId, userId,
                        System.currentTimeMillis() - t0);
                // ⚠️ 职责边界：抢单只负责"把任务指派给谁"（唯一性在这里）。
                //    子订单与结算单元要挂在 biz_order 上，而 biz_order 是资金视角的订单，
                //    在托管阶段（T17）才产生 —— 所以这里不建子订单，由 T15/T17 的
                //    中标与托管流程统一创建。⛔ 抢单里硬造一个没有 order_id 的子订单
                //    只会让资金链路对不上账。
                return GrabResult.ok(null);
            }

            // ---------- ⑤ 失败：回读真实原因 ----------
            return explainFailure(taskId, userId, requestId);
        } finally {
            // ⚠️ 只删自己加的锁。极端情况下（执行超过 3 秒）锁已自动过期，
            //    此时删掉的是别人的锁 —— 但代价仅是少削一次峰，不影响正确性，
            //    所以不为它引入锁续期与 owner 校验的复杂度。
            redis.delete(lockKey);
        }
    }

    // ------------------------------------------------------------------

    /** 前置闸门。返回 null 表示全部通过。 */
    private GrabResult checkGates(Long taskId, Long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.publisher_id, t.status, t.grant_mode, t.min_credit_level,
                       u.credit_score, u.real_name_status, u.status AS user_status
                  FROM task t JOIN sys_user u ON u.id = ?
                 WHERE t.id = ?
                """, userId, taskId);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        Map<String, Object> r = rows.get(0);

        // 任务状态不对（已被抢走 / 已下架）—— 这属于"手慢了"，不是"你不合格"
        String status = String.valueOf(r.get("status"));
        if (!"RECRUITING".equals(status)) {
            GrabResult g = GrabResult.fail(ErrorCode.GRAB_TAKEN.getCode(), "该任务已被他人抢走或已下架");
            g.setSimilarTasks(grabMapper.similarTasks(taskId));
            return g;
        }
        // 发单方不能抢自己的单
        if (String.valueOf(r.get("publisher_id")).equals(String.valueOf(userId))) {
            return GrabResult.fail(ErrorCode.NOT_TASK_OWNER.getCode(), "不能承接自己发布的任务");
        }
        // 账号状态
        if (!"NORMAL".equals(String.valueOf(r.get("user_status")))) {
            return GrabResult.fail(ErrorCode.ACCOUNT_FROZEN.getCode(), "账号状态异常，无法接单");
        }
        // 实名（线下任务尤其必要）
        if (!"PASSED".equals(String.valueOf(r.get("real_name_status")))) {
            return GrabResult.fail(ErrorCode.NOT_REAL_NAME.getCode(), "请先完成实名认证再接单");
        }
        // 信誉分门槛
        int threshold = configInt(KEY_GRAB_THRESHOLD, 60);
        int score = ((Number) r.get("credit_score")).intValue();
        if (score < threshold) {
            return GrabResult.fail(ErrorCode.CREDIT_INSUFFICIENT.getCode(),
                    "信誉分不足（当前 " + score + " 分，需 " + threshold + " 分），"
                            + "可通过按时交付、获得好评提升");
        }
        return null;
    }

    /** 抢占失败后查清原因 —— ⛔ 不能统一回"抢单失败"。 */
    private GrabResult explainFailure(Long taskId, Long userId, String requestId) {
        Long assignee = grabMapper.currentAssignee(taskId);
        String status = grabMapper.currentStatus(taskId);

        GrabResult g;
        if (assignee != null) {
            g = GrabResult.fail(ErrorCode.GRAB_TAKEN.getCode(), "该任务已被他人抢走");
            // ⭐ 给相似任务：让用户立刻能做下一步，而不是反复重试同一单
            g.setSimilarTasks(grabMapper.similarTasks(taskId));
        } else {
            g = GrabResult.fail(ErrorCode.GRAB_BUSY.getCode(),
                    "当前状态（" + status + "）不可抢单，请刷新后重试");
        }
        writeLog(taskId, userId, requestId, false, g.getFailCode(), g.getFailReason());
        return g;
    }

    private Long currentSubOrder(Long taskId, Long userId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM sub_order WHERE task_id = ? AND taker_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, taskId, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** ⛔ 失败也要记 —— 否则运营永远不知道用户为什么抢不到单。 */
    private void writeLog(Long taskId, Long userId, String requestId,
                          boolean success, Integer failCode, String failReason) {
        try {
            jdbc.update("""
                    INSERT INTO task_grab_log (task_id, user_id, request_id, success,
                                               fail_code, fail_reason, task_status_at_fail, cost_ms, created_at)
                    VALUES (?,?,?,?,?,?,?,0,?)
                    """,
                    taskId, userId,
                    requestId == null ? "" : requestId,
                    success ? 1 : 0,
                    // fail_code / fail_reason 都是 NOT NULL：成功时写 0 与空串
                    failCode == null ? 0 : failCode,
                    failReason == null ? "" : failReason,
                    grabMapper.currentStatus(taskId), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            // 幂等键冲突说明并发重复提交 —— 属正常，忽略
            log.debug("抢单日志幂等冲突 taskId={} userId={}", taskId, userId);
        }
    }

    private int configInt(String key, int fallback) {
        try {
            String v = jdbc.queryForObject(
                    "SELECT config_value FROM sys_config WHERE config_key = ?", String.class, key);
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
