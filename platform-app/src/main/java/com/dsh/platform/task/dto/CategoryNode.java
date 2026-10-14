package com.dsh.platform.task.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 分类节点（供前端渲染树 + 供任务创建做约束校验）。
 *
 * <p>⭐ 把 {@code allowForms} / {@code allowPayModes} 一并下发，前端就能<b>只显示允许的选项</b>，
 * 而不是让用户选完再报错。但 ⛔ 前端据此裁剪只是体验优化 ——
 * 服务端仍会在任务创建时用同一份配置再校验一遍（T9）。
 */
@Data
public class CategoryNode {

    private Long id;

    private Long parentId;

    /** 物化路径，如 {@code /1/12/}，便于按前缀查子树。 */
    private String path;

    private Integer level;

    private String code;

    private String name;

    private String icon;

    /** 允许的任务形态：ONLINE / OFFLINE / HYBRID。 */
    private List<String> allowForms = new ArrayList<>();

    /** 允许的付款方式：FIXED / BID / BY_QUANTITY / BY_DAY / MILESTONE。 */
    private List<String> allowPayModes = new ArrayList<>();

    /** 分类级费率覆盖。为空表示用通用规则（公益类恒为空，0% 由服务层白名单保证）。 */
    private BigDecimal commissionRate;

    /** 是否需要资质证明。 */
    private Boolean requireLicense;

    /** 审核级别：MACHINE / HUMAN。 */
    private String auditLevel;

    /** 该分类下任务默认是否需要签到。 */
    private Boolean needCheckinDefault;

    private Integer sortNo;

    private List<CategoryNode> children = new ArrayList<>();
}
