package com.dsh.platform.task.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 任务大厅检索条件。 */
@Data
public class HallQuery {

    private String keyword;

    private String categoryCode;

    private String taskForm;

    private BigDecimal amountMin;

    private BigDecimal amountMax;

    private String cursor;

    /** 每页条数（会被钳制到 1..50）。 */
    private Integer pageSize = 10;

    /** 当前用户 id（用于标记"我发布过/我收藏过"；⛔ 不用于过滤可见性，大厅天然公开）。 */
    private Long viewerId;
}