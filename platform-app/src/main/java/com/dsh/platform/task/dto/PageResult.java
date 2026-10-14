package com.dsh.platform.task.dto;

import lombok.Data;

import java.util.List;

/**
 * 游标分页结果。
 *
 * <p>⛔ 没有 total 字段 —— 这是刻意的：精确总数需要 {@code COUNT(*)}，
 * 而大厅的筛选组合很多、数据量会到百万级，为每页算一次总数不划算。
 * 前端用"还有没有下一页"（{@code cursor != null}）驱动无限滚动即可。
 */
@Data
public class PageResult<T> {

    private List<T> list;

    /** 下一页游标；为 null 表示已到末页。 */
    private String cursor;

    private boolean hasMore;

    public static <T> PageResult<T> of(List<T> list, String cursor) {
        PageResult<T> r = new PageResult<>();
        r.setList(list);
        r.setCursor(cursor);
        r.setHasMore(cursor != null);
        return r;
    }
}