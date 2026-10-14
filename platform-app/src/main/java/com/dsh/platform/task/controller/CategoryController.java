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
 * <p>⛔ 这些是公开只读接口：发单页与筛选器在<b>未登录</b>时也要能渲染。
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