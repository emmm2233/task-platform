package com.dsh.platform.task.service;

import com.dsh.platform.common.page.CursorCodec;
import com.dsh.platform.task.dto.HallQuery;
import com.dsh.platform.task.dto.PageResult;
import com.dsh.platform.task.dto.TaskSummary;
import com.dsh.platform.task.statemachine.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务大厅检索。
 *
 * <p>⭐ <b>游标（键集）分页</b>，不是 offset 分页。理由见 {@link CursorCodec} 的类注释：
 * 大厅按发布时间倒序、而任务随时在增删，offset 分页会让"翻页时有人发了新任务"变成重复显示。
 *
 * <p>排序固定为 {@code (created_at DESC, id DESC)}：
 * <ul>
 *   <li>加 {@code id} 是<b>去重键</b> —— 同一毫秒插入的多条任务 {@code created_at} 相同，
 *       只按它排序时数据库返回顺序不稳定，翻页会重复或漏项；</li>
 *   <li>⛔ 不支持任意排序字段：每种排序都要有对应复合索引，
 *       否则会退化成 filesort，在百万级数据上直接拖垮大厅。</li>
 * </ul>
 *
 * <p>⛔ 只返回 {@code RECRUITING} 与 {@code BIDDING} —— 大厅是"可接的任务"列表，
 * 草稿、待审核、已结算、已取消都不该出现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskQueryService {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final JdbcTemplate jdbc;

    public PageResult<TaskSummary> hall(HallQuery q) {
        HallQuery query = q == null ? new HallQuery() : q;
        int pageSize = clampPageSize(query.getPageSize());

        // ① 筛选指纹：换筛选条件后复用旧游标会被拒（否则会取到不连续的一段）
        String fingerprint = CursorCodec.fingerprint(
                query.getKeyword(), query.getCategoryCode(), query.getTaskForm(),
                query.getAmountMin(), query.getAmountMax());

        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.task_no, t.title, t.task_form, t.pay_mode, t.status,
                       t.price, t.budget_min, t.budget_max, t.deadline, t.created_at,
                       c.code AS category_code, c.name AS category_name,
                       u.nickname AS publisher_nickname
                  FROM task t
                  JOIN task_category c ON c.id = t.category_id
                  LEFT JOIN sys_user u ON u.id = t.publisher_id
                 WHERE t.status IN ('RECRUITING', 'BIDDING')
                """);
        List<Object> args = new ArrayList<>();

        if (notBlank(query.getKeyword())) {
            sql.append(" AND t.title LIKE ?");
            args.add("%" + query.getKeyword().trim() + "%");
        }
        if (notBlank(query.getCategoryCode())) {
            // 物化路径前缀匹配，一级分类能带出全部子分类的任务
            sql.append(" AND (c.code = ? OR c.path LIKE CONCAT((SELECT path FROM task_category WHERE code = ?), '%'))");
            args.add(query.getCategoryCode());
            args.add(query.getCategoryCode());
        }
        if (notBlank(query.getTaskForm())) {
            sql.append(" AND t.task_form = ?");
            args.add(query.getTaskForm());
        }
        if (query.getAmountMin() != null) {
            sql.append(" AND t.price >= ?");
            args.add(query.getAmountMin());
        }
        if (query.getAmountMax() != null) {
            sql.append(" AND t.price <= ?");
            args.add(query.getAmountMax());
        }

        // ② 键集条件：严格小于上一页最后一条的 (created_at, id)
        if (notBlank(query.getCursor())) {
            CursorCodec.Cursor cursor = CursorCodec.decode(query.getCursor(), fingerprint);
            LocalDateTime key = LocalDateTime.parse(cursor.sortKey(), ISO);
            sql.append(" AND (t.created_at < ? OR (t.created_at = ? AND t.id < ?))");
            args.add(key);
            args.add(key);
            args.add(cursor.id());
        }

        // ③ 多取一条用于判断"还有没有下一页"，避免额外的 COUNT 查询
        sql.append(" ORDER BY t.created_at DESC, t.id DESC LIMIT ?");
        args.add(pageSize + 1);

        List<TaskSummary> rows = jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());

        boolean hasMore = rows.size() > pageSize;
        List<TaskSummary> page = hasMore ? new ArrayList<>(rows.subList(0, pageSize)) : rows;

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            TaskSummary last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(
                    last.getPublishedAt().format(ISO), last.getTaskId(), fingerprint);
        }
        return PageResult.of(page, nextCursor);
    }

    private static int clampPageSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE, size));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 行映射。
     *
     * <p>⭐ 状态中文名与分组从 {@link TaskStatus} 枚举取，而不是在 SQL 里写 CASE WHEN ——
     * 否则每加一个状态就要改 SQL，且四处 SQL 会各写一份、迟早不一致。
     */
    private static final RowMapper<TaskSummary> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        TaskSummary s = new TaskSummary();
        s.setTaskId(rs.getLong("id"));
        s.setTaskNo(rs.getString("task_no"));
        s.setTitle(rs.getString("title"));
        s.setTaskForm(rs.getString("task_form"));
        s.setPayMode(rs.getString("pay_mode"));

        String status = rs.getString("status");
        s.setStatus(status);
        try {
            TaskStatus st = TaskStatus.valueOf(status);
            s.setStatusName(st.getCnName());
            s.setStatusGroup(st.getGroup().name());
        } catch (IllegalArgumentException e) {
            // 库里出现了状态机不认识的值 —— 不要静默降级成 null，否则前端拿到空标签
            s.setStatusName(status);
            s.setStatusGroup("UNKNOWN");
        }

        s.setPrice(toBigDecimal(rs, "price"));
        s.setBudgetMin(toBigDecimal(rs, "budget_min"));
        s.setBudgetMax(toBigDecimal(rs, "budget_max"));
        s.setDeadline(toLocalDateTime(rs, "deadline"));
        s.setPublishedAt(toLocalDateTime(rs, "created_at"));
        s.setCategoryCode(rs.getString("category_code"));
        s.setCategoryName(rs.getString("category_name"));
        s.setPublisherNickname(rs.getString("publisher_nickname"));
        return s;
    };

    private static BigDecimal toBigDecimal(ResultSet rs, String col) throws SQLException {
        return rs.getBigDecimal(col);
    }

    private static LocalDateTime toLocalDateTime(ResultSet rs, String col) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toLocalDateTime();
    }
}
