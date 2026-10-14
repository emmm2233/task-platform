package com.dsh.platform.task.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务创建请求。
 *
 * <p>⚠️ 这里的 Bean Validation 只做【格式】校验（非空、长度、正数）；
 * 真正的【业务规则】校验在 {@code TaskTypeValidator} 里，
 * 因为它需要读分类配置与数据库 —— 那些是注解表达不了的。
 * ⛔ 不要把业务规则硬塞进注解：注解里拿不到 category.allow_forms。
 */
@Data
public class TaskCreateRequest {

    /** 三级分类 code（T8 提供，取值与约束都从它来）。 */
    @NotBlank(message = "请选择任务分类")
    private String categoryCode;

    @NotBlank
    @Size(max = 100, message = "标题不超过 100 字")
    private String title;

    @NotBlank
    @Size(max = 5000, message = "描述不超过 5000 字")
    private String description;

    @Size(max = 2000)
    private String deliverableDesc;

    /** 任务形态：ONLINE / OFFLINE / HYBRID。 */
    @NotBlank(message = "请选择任务形态")
    private String taskForm;

    /** 付款方式：FIXED / BID / BY_QUANTITY / BY_DAY / MILESTONE。 */
    @NotBlank(message = "请选择付款方式")
    private String payMode;

    /** 接单方式：GRAB（抢单）/ BID（竞拍）。 */
    @NotBlank(message = "请选择接单方式")
    private String grantMode;

    /** 中标方式：SINGLE / MULTI（Phase 1 仅 SINGLE 可用）。 */
    private String awardMode = "SINGLE";

    /** MULTI 时需要的接单方数量。 */
    private Integer requiredCount;

    // ---- 各付款方式对应的金额字段（按 payMode 条件必填）----
    private BigDecimal price;
    private BigDecimal budgetMin;
    private BigDecimal budgetMax;
    private BigDecimal unitPrice;
    private Integer quantity;
    private String unit;
    private BigDecimal dayRate;
    private Integer days;
    private BigDecimal expenseCap;
    private Boolean expenseReimburse;

    @NotNull(message = "请设置截止时间")
    @Future(message = "截止时间必须晚于当前时间")
    private LocalDateTime deadline;

    private LocalDateTime startAt;
    private LocalDateTime bidDeadline;

    /** 验收期天数（默认 7，G11）。 */
    private Integer acceptDays = 7;

    /** ⭐ 验收标准：<b>至少 1 项</b>。没有它，恶意驳回时仲裁没有任何客观依据。 */
    @Valid
    private List<AcceptanceItem> acceptanceItems;

    private List<Milestone> milestones;

    private Location location;

    /** 线下/混合任务的履约时段，如 ["09:00-12:00"]。 */
    private List<String> serviceTimeSlots;

    private List<Attachment> attachments;

    private Boolean allowPartialDelivery;
    private Integer modifyRoundLimit;
    private String minCreditLevel;
    private String ipOwnership;

    @Data
    public static class AcceptanceItem {
        @NotBlank(message = "验收项名称不能为空")
        @Size(max = 200)
        private String itemName;
        @Size(max = 1000)
        private String itemDesc;
        private Boolean required = true;
        private Integer weight;
    }

    @Data
    public static class Milestone {
        @NotNull
        private Integer seq;
        @NotBlank
        private String name;
        @NotNull
        @DecimalMin(value = "0.01", message = "里程碑金额必须大于 0")
        private BigDecimal amount;
        private BigDecimal prepayRatio;
        private String deliverableDesc;
        private LocalDateTime deadline;
    }

    @Data
    public static class Location {
        private String province;
        private String city;
        private String district;
        private String address;
        private BigDecimal lng;
        private BigDecimal lat;
        private Integer serviceRadiusKm;
        private Boolean needCheckin;
    }

    @Data
    public static class Attachment {
        private String fileName;
        private String fileKey;
        private Long fileSize;
        private String contentType;
    }
}