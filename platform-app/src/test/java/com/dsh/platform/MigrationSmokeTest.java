package com.dsh.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 · 迁移冒烟测试：验证 {@code db/migration/V1–V7} 能真正落到数据库上。
 *
 * <p><b>⚠️ 环境偏差</b>：计划原写用 Testcontainers（需要 Docker），
 * 但本机未安装 Docker —— 改为直连<b>本机已在运行的 MySQL</b>，库名 {@code task_platform_test}。
 * 有 Docker 的机器可用 {@code -Dtest.mysql.url=...} 覆盖；CI 建议换回 Testcontainers。
 *
 * <p>本测试会 <b>clean 目标库</b>（⛔ 只允许指向 *_test 库，见 {@link #assertSafeDatabase()}）。
 */
class MigrationSmokeTest {

    private static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3306/task_platform_test"
                    // ⚠️ UTF-8 是 Java 字符集名；⛔ utf8mb4 是服务端字符集名，驱动会报 Unsupported
                    + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
                    + "&useSSL=false&allowPublicKeyRetrieval=true";

    /**
     * ⭐ 连接参数的取值顺序（与 20 个 {@code @SpringBootTest} <b>同源</b>）。
     *
     * <p>⛔ 修复前只读 {@code test.mysql.url}，而 surefire 注入的是
     * {@code spring.datasource.url}（来自 pom 的 {@code test.db.url} 属性）——
     * 于是 CI 传 {@code -Dtest.db.url=jdbc:mysql://mysql:3306/...} 时，
     * Spring 测试连得上、<b>本类却退回 127.0.0.1</b>（容器里的自己）⇒ 必红。
     * 现在两者共用同一个来源，本机与 CI 都不会漂。
     */
    static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    private static final String USERNAME = firstNonBlank(
            System.getProperty("test.mysql.username"),
            System.getProperty("spring.datasource.username"),
            "root");
    private static final String PASSWORD = firstNonBlank(
            System.getProperty("test.mysql.password"),
            System.getProperty("spring.datasource.password"),
            "123456");

    /** 与 db/migration 实跑结果一致的期望值。 */
    private static final int EXPECTED_TABLES = 71;
    private static final int EXPECTED_MIGRATIONS = 8;

    private static String url;
    private static MigrateResult migrateResult;

    /**
     * 一次性 clean + migrate，后续所有测试断言的都是这份已迁移好的库。
     *
     * <p>⛔ 不要让每个测试各调一次 migrate —— 那样测试之间会互相依赖执行顺序，
     * 且未调 migrate 的测试会看到空库（本测试第一版就踩了这个坑）。
     */
    @BeforeAll
    static void migrateOnce() {
        // ⭐ 与 @SpringBootTest 同源：surefire 注入的 spring.datasource.url 优先，
        //    再退回 test.mysql.url（显式覆盖），最后才是本机默认值。
        url = firstNonBlank(
                System.getProperty("test.mysql.url"),
                System.getProperty("spring.datasource.url"),
                DEFAULT_URL);
        assertSafeDatabase();

        Flyway flyway = flyway();
        flyway.clean();                       // 保证可重复：每次从空库开始
        migrateResult = flyway.migrate();
    }

    /** ⛔ 防呆：clean 是破坏性操作，绝不允许指向非测试库。 */
    private static void assertSafeDatabase() {
        assertThat(url)
                .as("MigrationSmokeTest 会 clean 目标库，URL 必须以 _test 结尾")
                .contains("_test");
    }

    @Test
    @DisplayName("Flyway 能按序执行全部 7 个迁移，且无失败记录")
    void flyway_applies_all_migrations() {
        assertThat(migrateResult.success).isTrue();
        assertThat(migrateResult.migrationsExecuted)
                .as("应执行 %d 个迁移，实际 %d", EXPECTED_MIGRATIONS, migrateResult.migrationsExecuted)
                .isEqualTo(EXPECTED_MIGRATIONS);
    }

    @Test
    @DisplayName("迁移后表数量与 db/migration 实跑结果一致（71 张）")
    void creates_expected_number_of_tables() throws Exception {
        assertThat(countTables()).isEqualTo(EXPECTED_TABLES);
    }

    @Test
    @DisplayName("关键的库层不变量约束确实存在（配平 / 份额 / 费率 / 信誉分区间）")
    void critical_check_constraints_exist() throws Exception {
        List<String> constraints = listCheckConstraints();

        assertThat(constraints)
                .as("⭐ 佣金配平不变量：佣金 + 实得 = 基数")
                .contains("ck_comm_balanced");
        assertThat(constraints)
                .as("⭐ 托管份额不超发（P0-① 修复项）")
                .contains("ck_share_sum");
        assertThat(constraints)
                .as("⭐ 费率钳制 1%–20%")
                .contains("ck_comm_rate");
        assertThat(constraints)
                .as("⭐ 信誉分区间 0–100")
                .contains("ck_user_credit_range");
    }

    @Test
    @DisplayName("审计表带按小时分段的哈希链字段与唯一键（P0-③ 修复项）")
    void audit_tables_have_hash_chain_columns() throws Exception {
        for (String table : List.of("sys_audit_log", "sys_data_access_log")) {
            assertThat(listColumns(table))
                    .as("%s 必须含 4 个链字段", table)
                    .contains("chain_hour", "chain_seq", "prev_hash", "row_hash");
            assertThat(listIndexes(table))
                    .as("%s 必须有 uk_chain（段内序号唯一，并发不写坏链）", table)
                    .contains("uk_chain");
        }
    }

    @Test
    @DisplayName("⭐ 短信防轰炸的两个闸门参数已种子化（V8）")
    void sms_guardrail_configs_are_seeded() throws Exception {
        // ⛔ 这是硬断言，V8 末尾的 SELECT 只打印、不会让迁移失败
        //    （Flyway 只按 SQL 错误判定成败）——真正的门禁必须在测试里。
        assertThat(queryInt("SELECT COUNT(*) FROM sys_config WHERE config_key IN "
                + "('security.sms_global_hour_limit','security.sms_verify_max_attempts')"))
                .as("全局短信上限 + 校验尝试上限，缺任何一个都会让对应防护静默失效")
                .isEqualTo(2);
        assertThat(queryInt("SELECT CAST(config_value AS UNSIGNED) FROM sys_config "
                + "WHERE config_key = 'security.sms_verify_max_attempts'"))
                .as("校验尝试上限必须是正整数（0 会让所有验证码都无法通过）")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("⭐ 通知模板的业务占位符被原样保存（Flyway 不得做占位符替换）")
    void notification_templates_keep_business_placeholders() throws Exception {
        String content = queryOne(
                "SELECT content FROM notify_template WHERE tpl_code = 'TASK_AWARDED' AND channel = 'INNER'");
        assertThat(content)
                .as("模板里的 ${taskTitle} 是**业务占位符**，由通知引擎在运行时替换；"
                        + "若 Flyway 开了 placeholder-replacement，这些会被当成 Flyway 占位符而报错或清空")
                .contains("${taskTitle}");
    }

    @Test
    @DisplayName("平台科目账户已初始化（佣金 / 保证金 / 托管池三类）")
    void platform_accounts_are_seeded() throws Exception {
        assertThat(queryInt("SELECT COUNT(*) FROM wallet_account WHERE user_id IS NULL"))
                .as("PLATFORM_COMMISSION / PLATFORM_DEPOSIT / ESCROW_HOLD 三类平台账户")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("应急开关齐备（缺了就没有一键止血能力）")
    void emergency_flags_are_seeded() throws Exception {
        assertThat(queryInt("SELECT COUNT(*) FROM sys_feature_flag WHERE emergency = 1"))
                .as("冻结提现 / 停分账 / 停代付 / 全站只读 / 禁发布")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("信誉等级（AUTO 档）分数区间连续覆盖 0–100 且不重叠")
    void credit_levels_are_continuous() throws Exception {
        List<int[]> bands = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT min_score, max_score FROM credit_level "
                            + "WHERE enabled = 1 AND award_mode = 'AUTO' ORDER BY min_score");
            while (rs.next()) {
                bands.add(new int[]{rs.getInt(1), rs.getInt(2)});
            }
        }
        assertThat(bands).as("AUTO 档不能为空").isNotEmpty();
        assertThat(bands.get(0)[0]).as("必须从 0 分开始").isZero();
        for (int i = 1; i < bands.size(); i++) {
            assertThat(bands.get(i)[0])
                    .as("第 %d 档起点应为上一档终点 +1（无缺口无重叠）", i + 1)
                    .isEqualTo(bands.get(i - 1)[1] + 1);
        }
        assertThat(bands.get(bands.size() - 1)[1]).as("必须覆盖到 100 分").isEqualTo(100);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(url, USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                // ⭐ 必须关闭：种子数据里的通知模板合法地包含 ${taskTitle} 这类**业务**占位符，
                //    它们是运行时由通知引擎替换的，⛔ 不是 Flyway 占位符。
                //    开启时 Flyway 会在解析阶段抛 "No value provided for placeholder"。
                .placeholderReplacement(false)
                .cleanDisabled(false)
                .load();
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(url, USERNAME, PASSWORD);
    }

    private static int countTables() throws Exception {
        // ⚠️ 必须排除 Flyway 自己的记账表 flyway_schema_history ——
        //    它不属于业务 schema。用 mysql CLI 的 `source` 直接跑时没有这张表，
        //    所以那次的统计是 71，接上 Flyway 后会变成 72（本测试第一版就踩了这个坑）。
        return queryInt("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'");
    }

    private static List<String> listCheckConstraints() throws Exception {
        // ⚠️ CHECK 约束名在 MySQL 中是 **schema 级唯一**，不是表级唯一
        return queryColumn("SELECT constraint_name FROM information_schema.table_constraints "
                + "WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'");
    }

    private static List<String> listColumns(String table) throws Exception {
        return queryColumn("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "'");
    }

    private static List<String> listIndexes(String table) throws Exception {
        // ⚠️ 复合索引在 statistics 里每列一行，必须去重
        return queryColumn("SELECT DISTINCT index_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "'");
    }

    private static List<String> queryColumn(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static int queryInt(String sql) throws Exception {
        try (Connection c = open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String queryOne(String sql) throws Exception {
        try (Connection c = open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
