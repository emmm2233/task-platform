package com.dsh.platform.identity.service;

import com.dsh.platform.identity.entity.SysDataAccessLog;
import com.dsh.platform.identity.mapper.SysDataAccessLogMapper;
import com.dsh.platform.identity.security.DataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataAccessAuditService {

    public static final String LEVEL_L3 = "L3";
    public static final String LEVEL_L4 = "L4";

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");

    private final SysDataAccessLogMapper mapper;
    private final Clock clock;

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
        LocalDateTime now = LocalDateTime.now(clock);
        String hour = now.atZone(ZoneOffset.ofHours(8)).format(HOUR);

        SysDataAccessLog l = new SysDataAccessLog();
        l.setOperatorId(operatorId);
        l.setTargetType(targetType);
        l.setTargetId(targetId);
        l.setTargetUser(targetUser);
        l.setDataLevel(dataLevel);
        l.setScene(scene);
        l.setRowsCount(1);
        l.setIp(ip);
        l.setTraceId(MDC.get("traceId"));
        l.setChainHour(hour);
        l.setChainSeq(nextSeq(hour));
        l.setPrevHash(prevHash(hour));
        l.setRowHash(hashOf(l, now));
        l.setCreatedAt(now);

        mapper.insert(l);
        log.info("L{} 数据访问 operator={} target={}#{} scene={}",
                dataLevel, operatorId, targetType, targetId, scene);
    }

    /**
     * 段内序号。
     *
     * <p>⚠️ 并发下两个事务可能取到同一个序号 —— 由 {@code uk_chain(chain_hour, chain_seq)}
     * 唯一键兜底：插入冲突时重试一次即可拿到新序号。
     * ⛔ 不要把序号获取写成"全局串行"，那会让审计成为整个系统的吞吐瓶颈。
     */
    private int nextSeq(String hour) {
        Long n = mapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysDataAccessLog>()
                        .eq("chain_hour", hour));
        return (n == null ? 0 : n.intValue()) + 1;
    }

    private String prevHash(String hour) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysDataAccessLog> w =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        w.eq("chain_hour", hour).orderByDesc("chain_seq").last("LIMIT 1");
        SysDataAccessLog last = mapper.selectOne(w);
        // 段首用 64 个 0 作为锚
        return last == null ? "0".repeat(64) : last.getRowHash();
    }

    /** 行哈希：把本条记录的关键字段与上一条的哈希串起来。 */
    private String hashOf(SysDataAccessLog l, LocalDateTime now) {
        String raw = String.join("|",
                String.valueOf(l.getOperatorId()),
                l.getTargetType(),
                String.valueOf(l.getTargetId()),
                String.valueOf(l.getTargetUser()),
                l.getDataLevel(),
                l.getChainHour(),
                String.valueOf(l.getChainSeq()),
                l.getPrevHash(),
                now.toString());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("审计行哈希计算失败", e);
        }
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
