package com.dsh.platform.identity.service;

import com.dsh.platform.identity.audit.HashChainService;
import com.dsh.platform.identity.entity.SysDataAccessLog;
import com.dsh.platform.identity.mapper.SysDataAccessLogMapper;
import com.dsh.platform.identity.security.DataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L3/L4 数据访问审计。
 *
 * <p>⭐ 核心原则：<b>看一眼就记一笔</b>。
 * 实名审核员每天要看几十张身份证 —— 没有访问审计，等于"谁看过"完全无从查证。
 * 等保三级明确要求「对重要数据的访问应可追溯」。
 *
 * <p>与业务审计同规格：写<b>按小时分段</b>的哈希链
 * （{@code chain_hour} + {@code chain_seq} + 唯一键），
 * 使并发写入不会写坏链（§6.7 的 P0-③）。
 *
 * <p>⛔ 本服务只记录"谁在什么时候看了哪条数据"，<b>不记录数据内容本身</b> ——
 * 审计日志里出现明文身份证号，等于把泄露面扩大了一倍。
 *
 * <h2>⛔⛔ 为什么这里必须调 {@link HashChainService}，不能自己算哈希</h2>
 * 本类曾经<b>自己</b>实现了一套取号 + 取上一条哈希 + 算 row_hash 的逻辑
 * （{@code nextSeq} / {@code prevHash} / {@code hashOf}），
 * 而校验方 {@link HashChainService#verify(String, String)} 用的是
 * {@code SPECS["sys_data_access_log"]} 那份字段清单。两份字段清单<b>不一样</b>：
 * <pre>
 *   本类（写入）  operatorId, targetType, targetId, targetUser, dataLevel,
 *                 chainHour, chainSeq, prevHash, <b>now.toString()</b>   ← 含 created_at
 *   校验方        operator_id, target_type, target_id, target_user, data_level,
 *                 scene, rows_count, ip, trace_id                       ← 无 created_at，多 4 个字段
 * </pre>
 * 于是校验方<b>永远重算不出</b>写入方的哈希 —— 独立审查实测：该表
 * <b>59 行 / 断 59 行（100%）</b>，{@code ChainVerifyJob} 每晚对每个段打
 * "🚨 审计链断裂" 并抛异常（狼来了，真篡改被淹没）。
 * 更彻底的是：连用<b>本类自己</b>的算法也复现不出任何一行 ——
 * {@code created_at} 是 {@code DATETIME(3)}，而 Java 的 {@code now()} 是微秒级，
 * 存进去被截断、读回来无法还原。{@link HashChainService} 的注释恰好预言了这个坑
 * （"参与哈希的字段必须取取回后的形态"）。
 *
 * <p>⭐ 结论：链的<b>唯一</b>实现是 {@link HashChainService}（计划文档里原本也是这么写的）。
 * 任何"我再写一份"的冲动都会让防篡改能力归零。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataAccessAuditService {

    public static final String LEVEL_L3 = "L3";
    public static final String LEVEL_L4 = "L4";

    /** 与 {@code HashChainService.SPECS} 的键一致 —— 表名写错会直接抛（specOf 有校验）。 */
    private static final String TABLE = "sys_data_access_log";

    private final SysDataAccessLogMapper mapper;
    private final HashChainService hashChain;

    /**
     * 记录一次敏感数据访问。
     *
     * @param operatorId 查看者
     * @param targetType REAL_NAME / BANK_CARD …
     * @param targetId   被查看记录的 id
     * @param targetUser 数据属于谁（用于"我的信息被谁看过"反查）
     * @param dataLevel  L3 / L4
     * @param scene      场景说明，如"实名审核"
     * @param ip         来源 IP
     */
    public void record(Long operatorId, String targetType, Long targetId, Long targetUser,
                       String dataLevel, String scene, String ip) {
        // ⚠️ 这里【不能】用 RowValues.of(...) 把 null 列省掉：
        //    省掉的列会由 DDL 的 DEFAULT '' 落库成空串，而哈希算的是 null 标记
        //    ⇒ 写入与校验不一致（正是本类上一版的病根）。
        //    ⭐ 全部字段显式给值，由 HashChainService.sanitize 在【算哈希之前】
        //      统一把 NOT NULL 列的 null 归一成 ""，保证"哈希的形态"就是"落库的形态"。
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("operator_id", operatorId);
        values.put("target_type", targetType);
        values.put("target_id", targetId);
        values.put("target_user", targetUser);
        values.put("data_level", dataLevel);
        values.put("scene", scene);
        values.put("rows_count", 1);
        values.put("ip", ip);
        values.put("trace_id", MDC.get("traceId"));

        hashChain.append(TABLE, values);
        log.info("L{} 数据访问 operator={} target={}#{} scene={}",
                dataLevel, operatorId, targetType, targetId, scene);
    }

    /** 供测试与排查：某条数据被谁看过几次。 */
    public int countByTarget(Long targetUser, String targetType) {
        Long n = mapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysDataAccessLog>()
                        .eq("target_user", targetUser).eq("target_type", targetType));
        return n == null ? 0 : n.intValue();
    }

    /** 便捷重载：用当前主体做 operator。 */
    public void recordAs(DataScope.Principal principal, String targetType, Long targetId,
                         Long targetUser, String dataLevel, String scene, String ip) {
        record(principal.userId(), targetType, targetId, targetUser, dataLevel, scene, ip);
    }
}
