package com.dsh.platform.task.dto;

import lombok.Builder;
import lombok.Data;

/** 任务创建结果。 */
@Data
@Builder
public class TaskCreateResult {

    private Long taskId;

    /** 对外展示的任务号，形如 T20261015000001。 */
    private String taskNo;

    /** 创建后的初始状态（DRAFT）。 */
    private String status;
}