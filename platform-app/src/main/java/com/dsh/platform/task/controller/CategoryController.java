package com.dsh.platform.task.controller;

import com.dsh.platform.common.ApiResponse;
import com.dsh.platform.task.dto.CategoryNode;
import com.dsh.platform.task.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类与字典接口。
 *
 * <p>⚠️ <b>当前需要登录</b>（{@code SecurityBaselineConfig} 是 deny-by-default，
 * 未对 {@code /api/v1/categories} 放行）。实测 {@code GET /api/v1/categories} 返回 401。
 *
 * <p>本类最初在此写着"这些是公开只读接口，未登录也要能渲染"——<b>那句话与实现不符</b>，
 * 是端到端核验（启动应用打真实接口）才发现的：单元测试直接调 Service，
 * 绕过了 HTTP 层与鉴权，所以永远不会暴露这类矛盾。
 *
 * <p>❓ <b>待定的产品决定</b>：任务大厅是否允许<b>匿名浏览</b>？
 * 允许（多数同类平台的做法，利于转化）→ 在 {@code SecurityBaselineConfig}
 * 里放行 {@code GET /api/v1/categories}、{@code GET /api/v1/tasks}；
 * 不允许 → 保持现状，但前端要处理"进首页先登录"。
 * ⛔ 在拍板之前不要照注释去改安全配置 —— 那会无意中把大厅对外开放。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 分类树。
     *
     * @param level 只取到第几级（默认 2，即带上子分类）
     */
    @GetMapping("/categories")
    public ApiResponse<List<CategoryNode>> tree(
            @RequestParam(name = "level", defaultValue = "2") int level) {
        return ApiResponse.ok(categoryService.tree(level));
    }

    @GetMapping("/categories/{code}")
    public ApiResponse<CategoryNode> byCode(@PathVariable("code") String code) {
        return ApiResponse.ok(categoryService.byCode(code));
    }
}