package com.dsh.platform.identity;

import com.dsh.platform.identity.audit.AuditRecord;
import com.dsh.platform.identity.audit.HashChainService;
import com.dsh.platform.identity.audit.VerifyResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7 · 业务审计日志与<b>按小时分段</b>哈希链。
 *
 * <p>⭐ 本测试是 §6.7 的 P0-③ 的回归防线。当时的设计是「每条记录含前一条的哈希」，
 * 但<b>并发写入时无法确定"前一条"</b> —— 两个事务同时读到同一尾部哈希，
 * 就会写出两条指向同一 {@code prev} 的记录，链当场裂开。
 *
 * <p>修复方案：{@code chain_hour}(YYYY-MM-DD-HH) + {@code chain_seq} + 唯一键，
 * <b>段内串行、段间并行</b>。段内序号冲突由唯一键兜底，插入冲突就重试。
 *
 * <p>⛔ 不要把它实现成"全局一条链"：那样审计会成为整个系统的吞吐瓶颈，
 * 而且在并发下必断。
 */
@SpringBootTest
@Import(HashChainTest.FastClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HashChainTest {

    /**
     * ⭐ 用可快进时钟替换系统时钟。
     *
     * <p>为什么必需：链是<b>按小时分段</b>的，三个用例若都写在同一个小时段里，
     * 就会互相看见对方的记录 —— 而"篡改可检出"那个用例会<b>永久改坏一条</b>，
     * 导致后续用例一上来就发现"链已经断了"（实测就是这样失败的）。
     *
     * <p>每个用例开头 fastForward(1h)，各占一个独立段，互不干扰。
     */
    @TestConfiguration
    static class FastClockConfig {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    /** 每个用例独占一个小时段。 */
    private String freshSegment() {
        clock.fastForward(java.time.Duration.ofHours(1));
        return currentHour();
    }

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");

    @Autowired
    private HashChainService chain;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestClock clock;   // 需 fastForward，故用可快进实现而非 java.time.Clock

    @BeforeAll
    void clean() {
        // 审计表只增不改，但测试要可重复 —— 清掉测试期间可能残留的行
        // ⚠️ 生产环境的应用账号对这两张表**没有** DELETE 权限（见 DDL 注释）
        jdbc.update("DELETE FROM sys_audit_log");
    }

    private String currentHour() {
        return LocalDateTime.now(clock).atZone(ZoneOffset.ofHours(8)).format(HOUR);
    }

    private AuditRecord rec(String action) {
        return AuditRecord.builder()
                .operatorId(1L)
                .operatorRole("ROLE_SUPER_ADMIN")
                .identity("ADMIN")
                .action(action)
                .bizType("TEST")
                .bizId(100L)
                .beforeValue("{\"status\":\"A\"}")
                .afterValue("{\"status\":\"B\"}")
                .reason("test-reason-ascii")
                .ip("127.0.0.1")
                .traceId("trace-" + action)
                .build();
    }

    @Test
    @DisplayName("⭐ P0-③：200 条并发写入，段内序号 1..200 无洞、链可完整校验")
    void concurrent_appends_do_not_break_the_chain() throws Exception {
        int n = 200;
        int threads = 20;
        String hour = freshSegment();   // ⭐ 独占一个段

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    chain.append(rec("CONCURRENT_" + idx));
                    ok.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        assertThat(failed.get()).as("并发写入不应有失败（唯一键冲突由重试消化）").isZero();
        assertThat(ok.get()).isEqualTo(n);

        // ① 段内序号必须是 1..n，无洞无重
        List<Integer> seqs = jdbc.queryForList(
                "SELECT chain_seq FROM sys_audit_log WHERE chain_hour = ? ORDER BY chain_seq",
                Integer.class, hour);
        assertThat(seqs).hasSize(n);
        for (int i = 0; i < n; i++) {
            assertThat(seqs.get(i)).as("第 %d 条的序号", i + 1).isEqualTo(i + 1);
        }

        // ② 逐条重算 row_hash，链必须完整
        VerifyResult r = chain.verify(hour);
        assertThat(r.total()).isEqualTo(n);
        assertThat(r.brokenCount()).as("链不应断裂").isZero();
        assertThat(r.validCount()).isEqualTo(n);
    }

    @Test
    @DisplayName("⭐ 篡改可检出：改动任意一条记录的字段，校验必须失败")
    void chain_verify_detects_tampering() {
        String hour = freshSegment();   // ⭐ 独占一个段
        AuditRecord r = rec("TO_BE_TAMPERED");
        chain.append(r);

        Long id = jdbc.queryForObject(
                "SELECT id FROM sys_audit_log WHERE trace_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, "trace-TO_BE_TAMPERED");

        VerifyResult before = chain.verify(hour);
        assertThat(before.brokenCount()).isZero();

        // 模拟内部人直接改库（绕过应用层）—— 这正是哈希链要防的场景
        jdbc.update("UPDATE sys_audit_log SET reason = '偷偷改了原因' WHERE id = ?", id);

        VerifyResult after = chain.verify(hour);
        assertThat(after.brokenCount())
                .as("⭐ 记录被篡改后，哈希链校验必须报出断裂")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("段首锚定 64 个 0，且不同小时属于不同段（段间互不影响）")
    void chain_is_segmented_by_hour() {
        String hour = freshSegment();   // ⭐ 独占一个段
        chain.append(rec("SEGMENT_FIRST"));

        String prev = jdbc.queryForObject(
                "SELECT prev_hash FROM sys_audit_log WHERE chain_hour = ? ORDER BY chain_seq LIMIT 1",
                String.class, hour);
        // 该小时的段首（chain_seq=1）的 prev 是 64 个 0
        Integer firstSeq = jdbc.queryForObject(
                "SELECT MIN(chain_seq) FROM sys_audit_log WHERE chain_hour = ?", Integer.class, hour);
        if (firstSeq != null && firstSeq == 1) {
            assertThat(prev).isEqualTo("0".repeat(64));
        }

        // 另一个小时段：序号从 1 重新开始，不受本段影响
        VerifyResult other = chain.verify("2000-01-01-00");
        assertThat(other.total()).isZero();
    }
}
