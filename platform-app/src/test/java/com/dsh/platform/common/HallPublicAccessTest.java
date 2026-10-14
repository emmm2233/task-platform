package com.dsh.platform.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 大厅匿名浏览（用户 2026-10-15 拍板：允许未登录浏览大厅）。
 *
 * <p>⭐⭐ 本测试最重要的断言不是"未登录能看分类"，而是
 * <b>"未登录【不能】发单"</b>。放行规则一旦写成不分方法的 permitAll，
 * 大厅是能看了，但发单接口也一起裸奔了 —— 而这个错误在"能看分类"的正向断言下
 * <b>完全不会暴露</b>。
 *
 * <p>⭐ 这也解释了为什么必须有这个测试类：{@code CategoryServiceTest} 的 7 个用例
 * 直接调 Service，绕过 HTTP 层与鉴权，结构上不可能发现这类问题
 * （这是上一轮端到端核验才暴露出来的）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HallPublicAccessTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("⭐ 未登录可读：分类树与单个分类")
    void anonymous_can_read_hall() throws Exception {
        mvc.perform(get("/api/v1/categories")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/categories/DEV")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("⭐⭐ 未映射路径必须 404 —— 不能被异常兜底变成 200")
    void unmapped_path_is_404_not_200() throws Exception {
        // 实测过：这里曾返回 200 + {"code":10999,"message":"系统繁忙"}，
        // 而 /api/v1/tasks 根本没有对应接口。controller 注册失败会被误判成"服务端故障"。
        // ① 在放行名单内、但没有对应接口 → 应该 404
        //    （实测过：曾经返回 200 + {"code":10999,"message":"系统繁忙"}）
        // ⚠️ 注意不要拿 /api/v1/tasks 当例子 —— 它【现在真的有接口了】
        //    （本用例最初就是这么写的，加了 TaskQueryController 之后立刻变红，
        //     这恰好说明断言是有效的：路由一变它就报）。
        //    这里用一个匹配 /api/v1/tasks/** 放行规则、但确实没有映射的子路径。
        mvc.perform(get("/api/v1/tasks/definitely-not-a-route")).andExpect(status().isNotFound());

        // ② 不在放行名单内、也没有对应接口 → 应该 401 而不是 404。
        //    ⭐ 这是对的行为：deny-by-default 先于路由判定，
        //    不向未登录者泄露"哪些路径存在"。若这里返回 404，
        //    攻击者就能靠状态码差异枚举出系统有哪些接口。
        mvc.perform(get("/api/v1/no-such-endpoint")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("⭐⭐ 未登录不可写：发单必须 401（放行不能连带放开 POST）")
    void anonymous_cannot_create_task() throws Exception {
        mvc.perform(post("/api/v1/tasks")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未登录仍不能访问运营与管理员域")
    void anonymous_cannot_reach_admin_or_op() throws Exception {
        mvc.perform(get("/api/v1/admin/sensitive-words")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/op/tasks")).andExpect(status().isUnauthorized());
    }
}
