package com.dsh.platform.identity.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 防篡改审计链 —— <b>按小时分段</b>（§6.7 的 P0-③ 修复）。
 *
 * <h2>为什么不能只做"一条全局链"</h2>
 * 最初的设想是「每条记录包含前一条的哈希」。问题在于<b>并发写入时无法确定"前一条"</b>：
 * 两个事务同时读到同一个尾部哈希，就会写出两条指向同一 {@code prev} 的记录 —— 链当场裂开。
 * 而且就算加了锁串行化，审计写入会成为整个系统的吞吐瓶颈。
 *
 * <h2>实际方案</h2>
 * <pre>
 *   chain_hour  YYYY-MM-DD-HH  —— 分段键
 *   chain_seq   段内序号 1,2,3…  —— 与 chain_hour 组成唯一键
 *   prev_hash   段内前一条的 row_hash（段首为 64 个 0）
 *   row_hash    SHA-256(规范化字段 ‖ chain_hour ‖ chain_seq ‖ prev_hash)
 *   UNIQUE KEY uk_chain (chain_hour, chain_seq)
 * </pre>
 * <b>段内串行、段间并行</b>。并发冲突交给唯一键：
 * 插入撞键就<b>重试</b>（重试时自然拿到新的 seq），⛔ 不需要任何分布式锁。
 *
 * <h2>为什么放在一个类里同时管两张表</h2>
 * 业务审计（{@code sys_audit_log}）与访问审计（{@code sys_data_access_log}）
 * <b>同规格</b> —— 谁看了谁的身份证，与谁改了哪条数据一样敏感。
 * 两份实现必然漂移，所以用同一套核心 + 各自的字段清单。
 *
 * <p>⚠️ {@code created_at} <b>不参与</b>哈希：DATETIME 精度在不同驱动/版本下
 * 可能被截断，纳入哈希会导致"读回来重算对不上"的假告警。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HashChainService {

    /** 表名 → 参与哈希的字段（顺序即规范化顺序，⛔ 不可随意调整）。 */
    private static final Map<String, List<String>> SPECS = Map.of(
            "sys_audit_log", List.of(
                    "operator_id", "operator_role", "identity", "action", "biz_type", "biz_id",
                    "before_value", "after_value", "reason", "ip", "user_agent", "trace_id"),
            "sys_data_access_log", List.of(
                    "operator_id", "target_type", "target_id", "target_user", "data_level",
                    "scene", "rows_count", "ip", "trace_id"));

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");
    private static final String GENESIS = "0".repeat(64);

    /**
     * 允许为 null 的字段 —— 其余文本字段一律把 null 归一成空串。
     *
     * <p>⭐ 为什么要在服务层做这件事，而不是要求调用方填全：
     * 审计表的文本列大多是 {@code NOT NULL DEFAULT ''}，而
     * <b>DEFAULT 只在列被省略时生效，显式插入 null 会直接报 1048</b>。
     * 若把"记得填 user_agent"的责任交给每个调用方，迟早有人漏 ——
     * 而<b>审计写不进去意味着那次操作没有留痕</b>，这是安全漏洞，不是小瑕疵。
     */
    private static final java.util.Set<String> NULLABLE_FIELDS =
            java.util.Set.of("biz_id", "before_value", "after_value");

    /**
     * ⭐ JSON 类型的列 —— 参与哈希前必须换成 <b>MySQL 规范化之后</b>的文本。
     *
     * <p>踩过的坑：{@code before_value} / {@code after_value} 在 DDL 里是 {@code json} 类型。
     * MySQL 存 JSON 时会做<b>规范化</b>（冒号后补空格、对象键按长度与字典序重排等），
     * 于是"插入前的字符串"与"读回来的字符串"<b>不是同一份字节</b> ——
     * 结果是整段链 100% 校验失败（实测 200/200 全断）。
     *
     * <p>修法：写入前先用 {@code CAST(? AS JSON)} 让 MySQL 给出它将要存储的形式，
     * 用它算哈希；校验时读回来的正是同一形式，两边自然一致。
     *
     * <p>⛔ 不要改用"从哈希里排除 JSON 字段"来绕开：那样改动 before/after 快照
     * 就不再能被检出了 —— 而"谁把这条数据从什么改成了什么"恰恰是审计最核心的内容。
     */
    private static final Map<String, List<String>> JSON_COLUMNS = Map.of(
            "sys_audit_log", List.of("before_value", "after_value"),
            "sys_data_access_log", List.of());

    /** 系统发起的动作（定时任务等）没有操作人，用 0 表示 —— operator_id 是 NOT NULL。 */
    private static final long SYSTEM_OPERATOR = 0L;

    /** 并发撞唯一键后的重试次数（仅用于<b>跨实例</b>冲突）。 */
    private static final int MAX_RETRY = 5;

    /**
     * 段锁：{@code 表名#小时段} → 监视器对象。
     *
     * <p>⭐ 为什么需要它：{@link #nextSeq} 读的是 {@code MAX(chain_seq)+1}，
     * 20 个线程会同时算出同一个序号，然后 19 个撞唯一键、重试、再撞 ——
     * 实测 5 次重试根本不够，表现为"并发写入大量失败"。
     *
     * <p>所以采用<b>两级</b>并发控制：
     * <ol>
     *   <li><b>本进程内</b>：按段加锁，把"取号 → 算哈希 → 插入"变成原子操作（本类的主要职责）；</li>
     *   <li><b>跨实例</b>：唯一键 {@code uk_chain(chain_hour, chain_seq)} + 重试兜底。</li>
     * </ol>
     * ⛔ 不要升级成分布式锁：审计写入不该依赖 Redis 可用性，
     * 而且段锁是<b>按小时分片</b>的，不同小时段之间完全并行，吞吐不是问题。
     *
     * <p>键的数量随时间线性增长但极慢（每表每小时 1 个），
     * 单机跑一年也就 1.7 万个小对象，无需清理。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> segmentLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    private Object lockFor(String table, String hour) {
        return segmentLocks.computeIfAbsent(table + "#" + hour, k -> new Object());
    }

    /** 当前小时段。 */
    public String currentHour() {
        return LocalDateTime.now(clock).atZone(ZoneOffset.ofHours(8)).format(HOUR);
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /** 追加一条业务审计。 */
    public void append(AuditRecord r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("operator_id", r.getOperatorId());
        v.put("operator_role", r.getOperatorRole());
        v.put("identity", r.getIdentity());
        v.put("action", r.getAction());
        v.put("biz_type", r.getBizType());
        v.put("biz_id", r.getBizId());
        v.put("before_value", r.getBeforeValue());
        v.put("after_value", r.getAfterValue());
        v.put("reason", r.getReason());
        v.put("ip", r.getIp());
        v.put("user_agent", r.getUserAgent());
        v.put("trace_id", r.getTraceId());
        append("sys_audit_log", v);
    }

    /**
     * 追加一条记录到指定表（含链字段）。
     *
     * <p>⭐ 并发安全靠<b>唯一键 + 重试</b>，不靠锁。
     */
    public void append(String table, Map<String, Object> values) {
        List<String> spec = specOf(table);
        String hour = currentHour();
        // ⭐ 顺序必须是：先让 MySQL 规范化 JSON 字段，再补 NOT NULL 默认值，最后才算哈希。
        Map<String, Object> safe = sanitize(normalizeJsonFields(table, values));

        // ⭐ 段内串行：把"取号 → 算哈希 → 插入"合成原子操作。
        //    没有这段，并发下大量撞唯一键且重试不够（实测 20 线程时失败过半）。
        synchronized (lockFor(table, hour)) {
            for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                int seq = nextSeq(table, hour);
                String prev = prevHash(table, hour, seq);
                String rowHash = hashOf(spec, safe, hour, seq, prev);

                Map<String, Object> row = new LinkedHashMap<>(safe);
                row.put("chain_hour", hour);
                row.put("chain_seq", seq);
                row.put("prev_hash", prev);
                row.put("row_hash", rowHash);
                row.put("created_at", LocalDateTime.now(clock));

                try {
                    insert(table, row);
                    return;
                } catch (DuplicateKeyException e) {
                    // 只有**跨实例**才会走到这里（本进程内已被上面的锁串行化）。
                    log.debug("审计链段内序号冲突（跨实例），重试 {}/{}（table={} hour={} seq={}）",
                            attempt, MAX_RETRY, table, hour, seq);
                }
            }
        }
        throw new IllegalStateException(
                "审计写入重试 " + MAX_RETRY + " 次仍冲突，table=" + table + " hour=" + hour);
    }

    /**
     * 把 JSON 列的值换成 MySQL 将要存储的规范化形式（一次往返搞定全部 JSON 列）。
     * ⛔ 只处理非 null 值：null 保持 null（JSON 列可空，插入空串反而会报错）。
     */
    private Map<String, Object> normalizeJsonFields(String table, Map<String, Object> values) {
        List<String> cols = JSON_COLUMNS.getOrDefault(table, List.of()).stream()
                .filter(c -> values.get(c) != null)
                .toList();
        if (cols.isEmpty()) {
            return values;
        }
        String sql = "SELECT " + String.join(", ",
                cols.stream().map(c -> "CAST(? AS JSON)").toList());
        Object[] args = cols.stream().map(values::get).toArray();

        Map<String, Object> out = new LinkedHashMap<>(values);
        jdbc.query(sql, rs -> {
            for (int i = 0; i < cols.size(); i++) {
                out.put(cols.get(i), rs.getString(i + 1));
            }
        }, args);
        return out;
    }

    /**
     * 归一化：把 NOT NULL 列的 null 换成安全值。
     * ⚠️ 必须在<b>计算哈希之前</b>做 —— 否则写入用归一值、校验时按原值重算，链会全段误报。
     */
    private static Map<String, Object> sanitize(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>(values.size());
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null && !NULLABLE_FIELDS.contains(k)) {
                v = "operator_id".equals(k) ? SYSTEM_OPERATOR : "";
            }
            out.put(k, v);
        }
        return out;
    }

    private void insert(String table, Map<String, Object> row) {
        String cols = String.join(",", row.keySet());
        String marks = String.join(",", row.keySet().stream().map(k -> "?").toList());
        jdbc.update("INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ")",
                row.values().toArray());
    }

    /**
     * 段内下一个序号。
     *
     * <p>用 {@code MAX + 1} 而<b>不是</b> {@code COUNT + 1}：
     * 若有人删除过中间某条（正常情况下应用账号没有 DELETE 权限），
     * COUNT 会算出重复序号并永久撞键。
     */
    private int nextSeq(String table, String hour) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(chain_seq), 0) FROM " + table + " WHERE chain_hour = ?",
                Integer.class, hour);
        return (max == null ? 0 : max) + 1;
    }

    private String prevHash(String table, String hour, int seq) {
        if (seq <= 1) {
            return GENESIS;   // 段首锚定
        }
        List<String> hashes = jdbc.queryForList(
                "SELECT row_hash FROM " + table + " WHERE chain_hour = ? AND chain_seq = ?",
                String.class, hour, seq - 1);
        return hashes.isEmpty() ? GENESIS : hashes.get(0);
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /**
     * 校验某一小时段的链完整性。
     *
     * <p>三项检查缺一不可：
     * <ol>
     *   <li>段内序号连续（1..n），既不能有洞也不能重复；</li>
     *   <li>每条记录的 {@code prev_hash} 与上一条的 {@code row_hash} 一致（段首为 64 个 0）；</li>
     *   <li>逐条重算哈希，与存储的 {@code row_hash} 一致。</li>
     * </ol>
     * 第 3 项才是真正防篡改的那一条 —— 只改业务字段、不动链字段的攻击会被它抓住。
     */
    public VerifyResult verify(String table, String hour) {
        List<String> spec = specOf(table);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + table + " WHERE chain_hour = ? ORDER BY chain_seq", hour);

        int total = rows.size();
        if (total == 0) {
            return VerifyResult.empty();
        }

        int valid = 0;
        int broken = 0;
        int firstBroken = -1;

        for (int i = 0; i < total; i++) {
            Map<String, Object> row = rows.get(i);
            int seq = ((Number) row.get("chain_seq")).intValue();
            String storedHash = str(row.get("row_hash"));
            String storedPrev = str(row.get("prev_hash"));

            boolean ok = true;

            // ① 序号必须连续
            if (seq != i + 1) {
                ok = false;
            }
            // ② 与上一条衔接（段首锚定 64 个 0）
            String expectPrev = (i == 0) ? GENESIS : str(rows.get(i - 1).get("row_hash"));
            if (!Objects.equals(storedPrev, expectPrev)) {
                ok = false;
            }
            // ③ 逐条重算
            String recomputed = hashOf(spec, row, hour, seq, storedPrev);
            if (!Objects.equals(storedHash, recomputed)) {
                ok = false;
            }

            if (ok) {
                valid++;
            } else {
                broken++;
                if (firstBroken < 0) {
                    firstBroken = seq;
                }
            }
        }
        return new VerifyResult(total, valid, broken, firstBroken);
    }

    /** 便捷重载：校验业务审计。 */
    public VerifyResult verify(String hour) {
        return verify("sys_audit_log", hour);
    }

    // ------------------------------------------------------------------
    // 规范化与哈希
    // ------------------------------------------------------------------

    /**
     * 把一行数据按字段清单规范化后计算哈希。
     *
     * <p>⭐ 写入与校验<b>共用这一个函数</b> —— 两份实现必然漂移，
     * 而哈希链一旦出现"写的时候一种算法、校验时另一种"，就会全段误报。
     *
     * <p>规范化规则：字段按清单顺序、以 {@code |} 连接；null 统一写成字面量
     * {@code \u0000N}，与空字符串区分开（否则 "空" 与 "不存在" 会算出同一个哈希）。
     */
    private String hashOf(List<String> spec, Map<String, Object> values,
                          String hour, int seq, String prevHash) {
        List<String> parts = new ArrayList<>(spec.size() + 3);
        for (String col : spec) {
            parts.add(normalize(values.get(col)));
        }
        parts.add(hour);
        parts.add(String.valueOf(seq));
        parts.add(prevHash);

        String canonical = String.join("|", parts);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("审计行哈希计算失败", e);
        }
    }

    private static String normalize(Object v) {
        if (v == null) {
            return "\u0000N";     // 与空字符串区分
        }
        if (v instanceof byte[] b) {
            return HexFormat.of().formatHex(b);
        }
        return String.valueOf(v);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static List<String> specOf(String table) {
        List<String> spec = SPECS.get(table);
        if (spec == null) {
            throw new IllegalArgumentException("未登记的审计表：" + table);
        }
        return spec;
    }
}
