package com.dsh.platform.task.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 大厅列表项。
 *
 * <p>⭐ 带上 {@code statusName} / {@code statusGroup}：让四端都不必硬编码状态字符串，
 * 也避免"新增一个状态，三端都要改代码"。
 */
@Data
public class TaskSummary {

    private Long taskId;

    private String taskNo;

    private String title;

    private String categoryCode;

    private String categoryName;

    private String taskForm;

    private String payMode;

    private String status;

    /** 状态中文名（如"招募中"）。 */
    private String statusName;

    /** 状态分组（如"RECRUITING"），前端据此配色。 */
    private String statusGroup;

    private BigDecimal price;

    private BigDecimal budgetMin;

    private BigDecimal budgetMax;

    private LocalDateTime deadline;

    private LocalDateTime publishedAt;

    private String publisherNickname;
}