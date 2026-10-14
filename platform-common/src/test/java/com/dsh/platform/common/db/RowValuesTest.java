package com.dsh.platform.common.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RowValues 的单测。
 *
 * <p>⭐ 这个类存在的唯一理由是"某一类 bug 不要再出现第 11 次"，
 * 所以测试直接照着那 10 次的实际形态写 —— 每一条断言都对应一次真实事故。
 */
class RowValuesTest {

    @Test
    @DisplayName("putIfPresent：null 不入 map（让库的 DEFAULT 生效），非 null 原样放入")
    void put_if_present_skips_nulls() {
        Map<String, Object> m = new LinkedHashMap<>();
        RowValues.putIfPresent(m, "task_no", "T001");
        RowValues.putIfPresent(m, "unit", null);          // 曾因它报 1048

        assertThat(m).containsOnlyKeys("task_no");
        assertThat(m.get("task_no")).isEqualTo("T001");
    }

    @Test
    @DisplayName("nz：文本列 null → 空串（user_agent / item_desc / trace_id / fail_reason 都栽过）")
    void nz_turns_null_into_empty_string() {
        assertThat(RowValues.nz(null)).isEmpty();
        assertThat(RowValues.nz("")).isEmpty();
        assertThat(RowValues.nz(" x ")).isEqualTo(" x ");
    }

    @Test
    @DisplayName("数值归一：null → 0（required_count / expense_reimburse / prepay_ratio 都栽过）")
    void numeric_nz_returns_zero() {
        assertThat(RowValues.nzInt(null)).isZero();
        assertThat(RowValues.nzLong(null)).isZero();
        assertThat(RowValues.nzDecimal(null)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(RowValues.nzDecimal(new BigDecimal("12.34"))).isEqualByComparingTo("12.34");
    }

    @Test
    @DisplayName("⭐ toJson：JSON 列必须收到合法 JSON 数组，而不是逗号分隔串")
    void to_json_produces_valid_json_array() {
        String json = RowValues.toJson(List.of("09:00-12:00", "14:00-18:00"));

        assertThat(json).isEqualTo("[\"09:00-12:00\",\"14:00-18:00\"]");
        // ⛔ 反面：逗号分隔串会被 MySQL 拒绝
        //    Invalid JSON text: The document root must not be followed by other values
        assertThat(json).startsWith("[").endsWith("]");
    }

    @Test
    @DisplayName("toJson：空列表给 []，不给 null（JSON 列同样可能非空约束）")
    void to_json_of_empty_list_is_empty_array() {
        assertThat(RowValues.toJson(List.of())).isEqualTo("[]");
        assertThat(RowValues.toJson((List<String>) null)).isEqualTo("[]");
    }

    @Test
    @DisplayName("of：交替的列名与值，自动跳过 null")
    void of_builds_map_skipping_nulls() {
        Map<String, Object> m = RowValues.of(
                "task_id", 1L,
                "seq", 1,
                "item_name", "功能可用",
                "item_desc", null,           // ← 就是它报过 1048
                "weight", null);

        assertThat(m).containsOnlyKeys("task_id", "seq", "item_name");
        assertThat(m.get("item_name")).isEqualTo("功能可用");
    }

    @Test
    @DisplayName("of：参数不是偶数就立刻抛（配置错误要在构建期炸）")
    void of_rejects_odd_argument_count() {
        assertThatThrownBy(() -> RowValues.of("a", 1, "b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("成对");
    }

    @Test
    @DisplayName("insertParts：列清单与占位符个数一致")
    void insert_parts_are_consistent() {
        Map<String, Object> m = RowValues.of("a", 1, "b", "x", "c", null);
        String[] parts = RowValues.insertParts(m);

        assertThat(parts[0]).isEqualTo("a,b");
        assertThat(parts[1]).isEqualTo("?,?");
        assertThat(parts[1].split(",")).hasSize(m.size());
    }

    @Test
    @DisplayName("insertParts：空映射直接抛，不生成非法 SQL")
    void insert_parts_rejects_empty() {
        assertThatThrownBy(() -> RowValues.insertParts(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
