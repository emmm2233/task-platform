package com.dsh.platform.task;

import com.dsh.platform.task.service.TaskQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务大厅的 HTTP 入口（`GET /api/v1/tasks`）。
 *
 * <p>⭐ 这个类补的是一个从 T12 起就挂着的缺口：`TaskQueryService` 早就实现了
 * 游标分页与筛选，但**一直没有 controller** —— 服务写好了、前端却调不到。
 *
 * <p>它的存在还有一个副作用：上一轮核验时 `GET /api/v1/tasks` 返回
 * {@code 200 + "系统繁忙"}（异常兜底所致），而当时根本没有这个接口。
 * 有了真实接口之后，那条路径才真正可验证。
 *
 * <p>三条断言分别对应三件事：
 * <ol>
 *   <li><b>未登录可访问</b> —— 大厅匿名浏览（用户已拍板）；</li>
 *   <li><b>响应结构稳定</b> —— {@code code/data/list/cursor} 的形状是前端契约，
 *       缺字段会让前端在运行时才炸；</li>
 *   <li><b>返回状态元数据</b> —— {@code statusName/statusGroup} 让四端
 *       ⛔ 不必硬编码状态字符串（新增状态时前端不用改代码）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskQueryControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TaskQueryService queryService;

    @Test
    @DisplayName("⭐ 未登录可访问大厅（匿名浏览），且返回统一信封")
    void anonymous_can_query_hall() throws Exception {
        mvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.list").isArray());
    }

    @Test
    @DisplayName("⭐ 筛选与分页参数被正确接收（不是收下就丢）")
    void filters_and_paging_are_accepted() throws Exception {
        mvc.perform(get("/api/v1/tasks")
                        .param("keyword", "绝对不存在的关键词xyz")
                        .param("categoryCode", "DEV")
                        .param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // 关键词不可能命中，列表必须为空 —— 若参数被忽略就会返回全部，测试立刻失败
                .andExpect(jsonPath("$.data.list").isEmpty());
    }

    @Test
    @DisplayName("⭐ 非法游标给参数错误（不是 500，也不是静默忽略后返回第一页）")
    void malformed_cursor_is_a_param_error() throws Exception {
        mvc.perform(get("/api/v1/tasks").param("cursor", "not-a-valid-cursor!!"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    @DisplayName("⭐ 列表项带 statusName / statusGroup（前端不硬编码状态字符串）")
    void list_items_carry_status_metadata() throws Exception {
        // 造一条可被大厅看到的任务（RECRUITING），再查出来
        String title = "大厅契约测试-" + Long.toString(System.nanoTime(), 36);
        Long publisherId = jdbcPublisher();
        Long categoryId = jdbcCategory();
        insertRecruiting(title, publisherId, categoryId);

        mvc.perform(get("/api/v1/tasks").param("keyword", title))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].status").value("RECRUITING"))
                .andExpect(jsonPath("$.data.list[0].statusName").value("招募中"))
                .andExpect(jsonPath("$.data.list[0].statusGroup").value("RECRUITING"));
    }

    // ------------------------------------------------------------------

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Long jdbcPublisher() {
        return jdbc.queryForObject("SELECT id FROM sys_user LIMIT 1", Long.class);
    }

    private Long jdbcCategory() {
        return jdbc.queryForObject("SELECT id FROM task_category WHERE code = 'DEV'", Long.class);
    }

    private void insertRecruiting(String title, Long publisherId, Long categoryId) {
        jdbc.update("""
                INSERT INTO task (task_no, publisher_id, category_id, category_path, task_form, pay_mode,
                                  title, description, grant_mode, award_mode, required_count,
                                  price, deadline, status, audit_status, source_client, version)
                VALUES (?,?,?,'/','ONLINE','FIXED', ?,?, 'GRAB','SINGLE',1, 1000.00, ?, 'RECRUITING','PASSED','web-user',0)
                """,
                "HQ" + Long.toString(System.nanoTime(), 36), publisherId, categoryId,
                title, "大厅契约测试", java.time.LocalDateTime.now().plusDays(10));
    }
}
