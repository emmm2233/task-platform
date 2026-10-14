package com.dsh.platform.task.controller;

import com.dsh.platform.common.ApiResponse;
import com.dsh.platform.task.dto.HallQuery;
import com.dsh.platform.task.dto.PageResult;
import com.dsh.platform.task.dto.TaskSummary;
import com.dsh.platform.task.service.TaskQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 任务大厅的 HTTP 入口。
 *
 * <p>⭐ 补的是一个从 T12 起就挂着的缺口：{@link TaskQueryService} 早已实现
 * 游标分页与筛选，但<b>一直没有 controller</b> —— 服务写好了、前端却调不到。
 * 这也是三端前端「一行代码都没有」的前置阻塞之一。
 *
 * <p>⚠️ <b>匿名可访问</b>（已按用户口径在 {@code SecurityBaselineConfig} 放行，
 * 且<b>按方法限定为 GET</b> —— 见那里的注释：不分方法的 permitAll 会连带放开 POST）。
 *
 * <p>⛔ <b>不要在响应体里加 total</b>：精确总数要 {@code COUNT(*)}，
 * 而大厅筛选组合多、数据量会到百万级，为每页算一次不划算。
 * 前端用"有没有下一页"（{@code cursor != null}）驱动无限滚动即可 ——
 * 这一点在 {@link PageResult} 的注释里也写明了。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TaskQueryController {

    private final TaskQueryService taskQueryService;

    /**
     * 任务大厅（游标分页）。
     *
     * <p>⚠️ 参数用 {@code @RequestParam(required = false)} 而不是把 {@link HallQuery}
     * 当模型属性绑定：Query 里有 {@code viewerId} 这类<b>不该由客户端指定</b>的字段，
     * 直接绑定会让调用方通过 {@code ?viewerId=1} 伪装成别人。
     * ⛔ 该字段将来若要用，必须从登录态取，不能从入参取。
     */
    @GetMapping("/tasks")
    public ApiResponse<PageResult<TaskSummary>> hall(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "categoryCode", required = false) String categoryCode,
            @RequestParam(name = "taskForm", required = false) String taskForm,
            @RequestParam(name = "amountMin", required = false) BigDecimal amountMin,
            @RequestParam(name = "amountMax", required = false) BigDecimal amountMax,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "pageSize", required = false) Integer pageSize) {

        HallQuery q = new HallQuery();
        q.setKeyword(keyword);
        q.setCategoryCode(categoryCode);
        q.setTaskForm(taskForm);
        q.setAmountMin(amountMin);
        q.setAmountMax(amountMax);
        q.setCursor(cursor);
        if (pageSize != null) {
            q.setPageSize(pageSize);
        }
        // ⛔ 刻意不接 viewerId：它决定"我发布过/我收藏过"的标记，
        //    只能从登录态取，从入参取等于允许冒充。
        return ApiResponse.ok(taskQueryService.hall(q));
    }
}
