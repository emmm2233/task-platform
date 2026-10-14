package com.dsh.platform.task;

import com.dsh.platform.task.dto.CategoryNode;
import com.dsh.platform.task.service.CategoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T8 · 任务分类树与分类级配置。
 *
 * <p>分类不只是"分个类"—— 它承载了四类<b>约束</b>，任务创建时据此校验：
 * <ol>
 *   <li>{@code allow_forms} —— 这类任务允许线上/线下/混合（跑腿只能线下）；</li>
 *   <li>{@code allow_pay_modes} —— 允许哪些付款方式（跑腿只能一口价，不能竞拍）；</li>
 *   <li>{@code commission_rate} —— 分类级费率覆盖（公益类为空，走服务层白名单返回 0）；</li>
 *   <li>{@code audit_level} —— 机审还是人审（教育/生活服务必须人审）。</li>
 * </ol>
 *
 * <p>⚠️ 与计划/设计文档的出入（以库为准）：
 * 计划里 T8 的断言写的是"一级分类 10 个"，<b>实际库里是 11 个</b>
 * （多一个 {@code OTHER} 其他）。总数 46 与文档一致（11 一级 + 35 二级）。
 * 本测试按<b>真实数据</b>断言，并把这条出入记在此处，避免后来者以为是回归。
 */
@SpringBootTest
class CategoryServiceTest {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("一级分类共 11 个，且公益类 WELFARE 在内")
    void category_tree_has_11_level1_including_welfare() {
        List<CategoryNode> tree = categoryService.tree(1);

        assertThat(tree).hasSize(11);
        assertThat(tree).extracting(CategoryNode::getCode)
                .contains("DEV", "DESIGN", "ERRAND", "WELFARE", "OTHER");
    }

    @Test
    @DisplayName("分类总数 46：11 个一级 + 35 个二级")
    void total_categories_match_seed() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_category", Integer.class)).isEqualTo(46);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_category WHERE level = 2", Integer.class)).isEqualTo(35);
    }

    @Test
    @DisplayName("⭐ 跑腿代办只能线下 + 一口价（分类约束是任务创建的校验依据）")
    void errand_only_allows_offline_form_and_fixed_pay() {
        CategoryNode errand = categoryService.byCode("ERRAND");

        assertThat(errand.getAllowForms()).containsExactly("OFFLINE");
        assertThat(errand.getAllowPayModes()).containsExactly("FIXED");
        // ⛔ 跑腿不该出现"竞拍"：不能让跑腿单进入竞价流程
        assertThat(errand.getAllowPayModes()).doesNotContain("BID");
    }

    @Test
    @DisplayName("⭐ 高风险分类强制人工审核（教育培训 / 生活服务 / 市场营销）")
    void high_risk_categories_require_human_audit() {
        for (String code : List.of("EDU", "LIFE", "MARKET", "WELFARE")) {
            assertThat(categoryService.byCode(code).getAuditLevel())
                    .as("%s 必须人工审核", code)
                    .isEqualTo("HUMAN");
        }
        // 低风险走机审，保证吞吐
        assertThat(categoryService.byCode("DEV").getAuditLevel()).isEqualTo("MACHINE");
    }

    @Test
    @DisplayName("⭐ 公益类没有费率规则（Q18：服务层白名单返回 0%，不靠规则表）")
    void welfare_has_no_commission_rule() {
        Long welfareId = categoryService.byCode("WELFARE").getId();

        Integer rules = jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_rule WHERE category_id = ?", Integer.class, welfareId);
        assertThat(rules)
                .as("公益类不该有费率规则行 —— 它的 0% 由服务层白名单保证，写入规则表反而会与 0% 冲突")
                .isZero();

        // 对照：非公益类通常有（这里只断言"公益没有"，不断言别人必须有）
        assertThat(categoryService.byCode("WELFARE").getCommissionRate()).isNull();
    }

    @Test
    @DisplayName("按父级取子分类：一级分类下能取到二级")
    void children_of_level1_are_loadable() {
        CategoryNode dev = categoryService.byCode("DEV");
        List<CategoryNode> children = categoryService.children(dev.getId());

        assertThat(children).isNotEmpty();
        assertThat(children).allSatisfy(c -> {
            assertThat(c.getLevel()).isEqualTo(2);
            assertThat(c.getParentId()).isEqualTo(dev.getId());
        });
        assertThat(children).extracting(CategoryNode::getCode).contains("DEV_BACKEND");
    }

    @Test
    @DisplayName("停用的分类不出现在树里（运营下架分类要立即生效）")
    void disabled_categories_are_excluded() {
        Long before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_category WHERE enabled = 1 AND level = 1", Long.class);

        jdbc.update("UPDATE task_category SET enabled = 0 WHERE code = 'OTHER'");
        try {
            List<CategoryNode> tree = categoryService.tree(1);
            assertThat(tree).extracting(CategoryNode::getCode).doesNotContain("OTHER");
            assertThat(tree).hasSize(before.intValue() - 1);
        } finally {
            jdbc.update("UPDATE task_category SET enabled = 1 WHERE code = 'OTHER'");
        }
    }
}
