package com.dsh.platform.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 任务分类（两级：一级 11 个 + 二级 35 个 = 46）。 */
@Data
@TableName("task_category")
public class TaskCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 0 表示一级分类。 */
    private Long parentId;

    /** 物化路径，便于按前缀查子树。 */
    private String path;

    private Integer level;

    private String code;

    private String name;

    private String icon;

    /** 逗号分隔：ONLINE,OFFLINE,HYBRID。 */
    private String allowForms;

    /** 逗号分隔：FIXED,BID,BY_QUANTITY,BY_DAY,MILESTONE。 */
    private String allowPayModes;

    /** 分类级费率覆盖；为空表示走通用规则（公益类恒为空）。 */
    private BigDecimal commissionRate;

    private Boolean requireLicense;

    /** MACHINE / HUMAN。 */
    private String auditLevel;

    private Boolean needCheckinDefault;

    private Integer sortNo;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}