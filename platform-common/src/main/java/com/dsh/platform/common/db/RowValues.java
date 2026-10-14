package com.dsh.platform.common.db;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写库前的值归一 —— <b>本项目踩过 10 次的同一个坑的收敛点</b>。
 *
 * <h2>坑是什么</h2>
 * 本项目的 DDL 里有大量 {@code NOT NULL DEFAULT ''} / {@code NOT NULL DEFAULT 0} 的列，
 * 而 <b>SQL 的 DEFAULT 只在"列被省略"时生效，显式插入 null 一律报 1048</b>。
 * 于是每次新写一个 insert，就大概率踩一次。
 *
 * <p>已经踩过的（跨 5 个任务）：
 * <pre>
 *   sys_audit_log.user_agent           credit_rule.description（DDL 阶段）
 *   task.required_count                task.expense_reimburse
 *   task_acceptance_item.item_desc     task_milestone.prepay_ratio
 *   task_status_log.trace_id           task_location.service_radius_km
 *   sub_order.order_id                 task_grab_log.fail_code
 * </pre>
 * 每次都是"补一个默认值"——治标。写进注释也拦不住，因为注释不是机制。
 *
 * <h2>两类解法（按场景选）</h2>
 * <table border="1">
 *   <caption>什么时候用哪个</caption>
 *   <tr><th>场景</th><th>用法</th><th>为什么</th></tr>
 *   <tr>
 *     <td>列多、多数列有 DEFAULT（如 {@code task} 表 60+ 列）</td>
 *     <td>{@link #putIfPresent}</td>
 *     <td>只插入真正提供了值的列，其余交给库的 DEFAULT</td>
 *   </tr>
 *   <tr>
 *     <td>列少、逐条插入（如各类子表、日志表）</td>
 *     <td>{@link #nz} / {@link #nzInt} / {@link #toJson}</td>
 *     <td>统一归一成空串 / 0 / JSON 数组，避免"逐列想默认值"</td>
 *   </tr>
 * </table>
 *
 * <h2>另一类坑：JSON 列不能用普通字符串</h2>
 * {@code before_value} / {@code service_time_slots} 等列是 {@code json} 类型，
 * 写 {@code "09:00-12:00"} 会报
 * {@code Invalid JSON text: The document root must not be followed by other values}，
 * 必须用 {@link #toJson} 序列化成 {@code ["09:00-12:00"]}。
 *
 * <p>⚠️ 更隐蔽的是<b>往返不一致</b>：MySQL 存 JSON 时会规范化（冒号后补空格、键重排），
 * 所以"插入前的字符串"与"读回来的字符串"不是同一份字节 ——
 * 参与哈希的字段必须取<b>取回后</b>的形态（见 {@code HashChainService} 的注释）。
 */
public final class RowValues {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RowValues() {
    }

    /**
     * 只在有值时放入 —— 主表插入用它，让库的 DEFAULT 生效。
     *
     * <p>⛔ 不要写成 {@code map.put(k, v)} 之后再统一清 null：
     * 那样调用方看不出"这一列可以不填"，反而更容易漏。
     */
    public static void putIfPresent(Map<String, Object> target, String column, Object value) {
        if (value != null) {
            target.put(column, value);
        }
    }

    /** null → 空串。用于 {@code NOT NULL DEFAULT ''} 的文本列。 */
    public static String nz(String s) {
        return s == null ? "" : s;
    }

    /** null → 0。用于 {@code NOT NULL DEFAULT 0} 的数值列。 */
    public static int nzInt(Integer v) {
        return v == null ? 0 : v;
    }

    /** null → 0。用于 {@code NOT NULL DEFAULT 0} 的金额/长整型列。 */
    public static long nzLong(Long v) {
        return v == null ? 0L : v;
    }

    /** null → 0。金额列的 {@link BigDecimal} 版本。 */
    public static BigDecimal nzDecimal(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 把字符串列表序列化成 JSON 数组 —— <b>JSON 类型的列必须用它</b>。
     *
     * <p>⛔ 不要用 {@code String.join(",", list)}：那会报 Invalid JSON text。
     */
    public static String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return JSON.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 JSON 列失败", e);
        }
    }

    /** 任意对象的 JSON 序列化（审计快照等）。 */
    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 JSON 失败", e);
        }
    }

    /**
     * 由列名与值的交替序列构造插入用的列值映射，<b>自动跳过 null</b>。
     *
     * <p>用法：{@code RowValues.of("task_no", no, "price", price, "unit", unit)}
     * —— {@code unit} 为 null 时该列不会出现在 INSERT 里，库的 DEFAULT 生效。
     *
     * <p>⛔ 参数个数必须是偶数，否则直接抛（配置错误要在构建期炸，而不是运行期写坏数据）。
     */
    public static Map<String, Object> of(Object... columnValuePairs) {
        if (columnValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("列名与值必须成对出现，实际给了 " + columnValuePairs.length + " 个参数");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < columnValuePairs.length; i += 2) {
            String col = String.valueOf(columnValuePairs[i]);
            putIfPresent(out, col, columnValuePairs[i + 1]);
        }
        return out;
    }

    /**
     * 生成 {@code INSERT INTO t (cols) VALUES (?,?,?)} 的列清单与占位符。
     *
     * @return 长度为 2 的数组：{@code [列清单, 占位符]}
     */
    public static String[] insertParts(Map<String, Object> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("至少需要一列");
        }
        return new String[]{
                String.join(",", columns.keySet()),
                String.join(",", java.util.Collections.nCopies(columns.size(), "?"))
        };
    }
}
