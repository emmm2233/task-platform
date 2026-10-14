package com.dsh.platform.identity.job;

import com.dsh.platform.identity.audit.HashChainService;
import com.dsh.platform.identity.audit.VerifyResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每日审计链完整性校验。
 *
 * <p>⭐ 这是"审计写了"与"审计可信"之间的那道保障。
 * 哈希链只在<b>被校验</b>时才有意义 —— 没有这个任务，链再漂亮也只是装饰。
 *
 * <p>校验范围：最近 48 小时的全部段（覆盖跨天与补跑）。
 * 发现断裂即告警 —— 断裂意味着<b>有人直接改过库</b>，属安全事件而非普通故障。
 *
 * <p>⛔ 不要把它写成"只校验今天"：跨天的那一段（23:00-00:00）会被漏掉，
 * 而凌晨正是内部人动手的高发时段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChainVerifyJob {

    /** 回溯校验的小时段数。 */
    private static final int LOOKBACK_HOURS = 48;

    private static final List<String> TABLES = List.of("sys_audit_log", "sys_data_access_log");

    private final HashChainService chain;
    private final JdbcTemplate jdbc;

    /** 每天 03:30 跑（业务低峰，且早于财务对账）。 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void run() {
        int checkedSegments = 0;
        int brokenSegments = 0;

        for (String table : TABLES) {
            List<String> hours = jdbc.queryForList(
                    "SELECT DISTINCT chain_hour FROM " + table
                            + " WHERE created_at >= DATE_SUB(NOW(), INTERVAL ? HOUR) ORDER BY chain_hour",
                    String.class, LOOKBACK_HOURS);

            for (String hour : hours) {
                checkedSegments++;
                VerifyResult r = chain.verify(table, hour);
                if (!r.isIntact()) {
                    brokenSegments++;
                    // ⛔ 用 ERROR 而不是 WARN：链断裂是安全事件，必须触发告警
                    log.error("🚨 审计链断裂 table={} hour={} 总 {} 条 / 断裂 {} 条 / 首条断裂在 seq={}",
                            table, hour, r.total(), r.brokenCount(), r.firstBrokenSeq());
                }
            }
            log.info("审计链校验完成 table={} 段数={}", table, hours.size());
        }

        log.info("审计链完整性校验结束：检查 {} 段，断裂 {} 段", checkedSegments, brokenSegments);
        if (brokenSegments > 0) {
            // 让调用方（XXL-Job / 监控）能感知失败
            throw new IllegalStateException("审计链有 " + brokenSegments + " 个段断裂，需立即排查");
        }
    }
}
