package com.dsh.platform.task.validator;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.task.dto.CategoryNode;
import com.dsh.platform.task.dto.TaskCreateRequest;
import com.dsh.platform.task.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 三维动态校验器（§5.14.5）。
 *
 * <p>"三维" = 业务分类 × 任务形态(task_form) × 付款方式(pay_mode)，
 * 组合不同则必填项与合法取值都不同。校验分五类，<b>顺序不可随意调换</b>：
 *
 * <pre>
 *   ① 分类约束   —— 取值必须落在该分类的 allow_forms / allow_pay_modes 内
 *   ② 互斥       —— 付款方式与接单方式必须配套
 *   ③ 条件必填   —— 选了 OFFLINE 才要地点与服务时段；选了 BY_QUANTITY 才要单价数量
 *   ④ 风险       —— 里程碑配平、MILESTONE × MULTI 互斥
 *   ⑤ 验收标准   —— 至少 1 项且名称非空
 * </pre>
 *
 * <p>⭐ 顺序的意义：<b>先判"这个组合本身合不合法"，再判"字段填全没有"</b>。
 * 反过来的话，用户会先看到"缺少地点"，补完地点才被告知"这个分类不支持线下" —— 白填一轮。
 *
 * <p>⛔ 所有失败都抛 {@link BizException} 且带<b>能指导下一步</b>的文案，
 * 不要只说"参数错误"—— 发单是长表单，用户需要知道改哪一项。
 */
@Component
@RequiredArgsConstructor
public class TaskTypeValidator {

    /** Phase 1 支持的付款方式。 */
    private static final List<String> SUPPORTED_PAY_MODES = List.of(
            "FIXED", "BID", "BY_QUANTITY", "BY_DAY", "MILESTONE");

    private final CategoryService categoryService;

    public void validate(TaskCreateRequest req) {
        CategoryNode category = categoryService.byCode(req.getCategoryCode());

        checkCategoryConstraints(req, category);
        normalizeAwardMode(req);
        checkMutualExclusion(req);
        checkConditionalRequired(req);
        checkRiskRules(req, category);
        checkAcceptanceItems(req);
    }

    /**
     * ⭐ 由 awardMode <b>推导</b> requiredCount，而不是让调用方填。
     *
     * <p>踩过的坑：库要求 {@code required_count} 非空，而 SINGLE 场景下调用方不会填它
     * （单选中标本来就只有一个接单方），结果插入报 {@code Column 'required_count' cannot be null}。
     *
     * <p>根因不是"忘了给默认值"，而是<b>把派生字段交给了调用方</b>：
     * 单选就是 1、多选才需要指定数量。这类字段一旦外露，就会有填错与漏填两种失败。
     */
    private void normalizeAwardMode(TaskCreateRequest req) {
        String awardMode = req.getAwardMode() == null ? "SINGLE" : req.getAwardMode();
        req.setAwardMode(awardMode);

        if ("SINGLE".equals(awardMode)) {
            req.setRequiredCount(1);          // 单选中标恒为 1，忽略调用方传的值
            return;
        }
        if (!"MULTI".equals(awardMode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知的中标方式：" + awardMode);
        }
        if (req.getRequiredCount() == null || req.getRequiredCount() < 2) {
            throw new BizException(ErrorCode.PARAM_INVALID, "多选中标需要指定接单方数量（至少 2 个）");
        }
    }

    // ① 分类约束 -------------------------------------------------------

    private void checkCategoryConstraints(TaskCreateRequest req, CategoryNode category) {
        if (!category.getAllowForms().isEmpty()
                && !category.getAllowForms().contains(req.getTaskForm())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "「" + category.getName() + "」不支持「" + formName(req.getTaskForm())
                            + "」形态，可选：" + String.join("、", category.getAllowForms()));
        }
        if (!category.getAllowPayModes().isEmpty()
                && !category.getAllowPayModes().contains(req.getPayMode())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "「" + category.getName() + "」不支持「" + payName(req.getPayMode())
                            + "」，可选：" + String.join("、", category.getAllowPayModes()));
        }
        if (!SUPPORTED_PAY_MODES.contains(req.getPayMode())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知的付款方式：" + req.getPayMode());
        }
    }

    // ② 互斥 -----------------------------------------------------------

    private void checkMutualExclusion(TaskCreateRequest req) {
        // 一口价 + 竞拍 = 价格已经定了还要大家报价，语义矛盾；且会给接单方"可以议价"的错觉
        if ("FIXED".equals(req.getPayMode()) && !"GRAB".equals(req.getGrantMode())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "一口价任务只能采用抢单方式；若要让大家报价，请把付款方式改为竞拍");
        }
        if (!"FIXED".equals(req.getPayMode()) && "GRAB".equals(req.getGrantMode())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "非一口价任务需要接单方报价，请把接单方式改为竞拍");
        }
    }

    // ③ 条件必填 -------------------------------------------------------

    private void checkConditionalRequired(TaskCreateRequest req) {
        boolean needsLocation = "OFFLINE".equals(req.getTaskForm()) || "HYBRID".equals(req.getTaskForm());
        if (needsLocation) {
            var loc = req.getLocation();
            if (loc == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "线下/混合任务必须填写服务地点");
            }
            if (isBlank(loc.getProvince()) || isBlank(loc.getCity()) || isBlank(loc.getAddress())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "服务地点需填写省、市与详细地址");
            }
            // ⭐ 没有坐标就无法算距离、无法按距离排序、无法签到校验
            if (loc.getLng() == null || loc.getLat() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "服务地点需要经纬度（否则无法计算距离、无法按距离筛选）");
            }
            if (req.getServiceTimeSlots() == null || req.getServiceTimeSlots().isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "线下/混合任务必须填写可服务时段");
            }
        }

        switch (req.getPayMode()) {
            case "FIXED" -> requirePositive(req.getPrice(), "一口价金额");
            case "BY_QUANTITY" -> {
                requirePositive(req.getUnitPrice(), "单价");
                if (req.getQuantity() == null || req.getQuantity() <= 0) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "按量计酬需要填写数量");
                }
                if (isBlank(req.getUnit())) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "按量计酬需要填写计量单位（如：件/篇/小时）");
                }
            }
            case "BY_DAY" -> {
                requirePositive(req.getDayRate(), "日薪");
                if (req.getDays() == null || req.getDays() <= 0) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "按天计酬需要填写天数");
                }
            }
            case "BID" -> {
                if (req.getBudgetMin() == null || req.getBudgetMax() == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "竞拍任务必须填写预算区间");
                }
                if (req.getBudgetMin().compareTo(req.getBudgetMax()) > 0) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "预算下限不能高于上限");
                }
            }
            case "MILESTONE" -> requirePositive(req.getBudgetMax(), "里程碑总预算");
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "未知的付款方式：" + req.getPayMode());
        }
    }

    // ④ 风险规则 -------------------------------------------------------

    private void checkRiskRules(TaskCreateRequest req, CategoryNode category) {
        if (!"MILESTONE".equals(req.getPayMode())) {
            return;
        }
        List<TaskCreateRequest.Milestone> ms = req.getMilestones();
        if (ms == null || ms.size() < 2) {
            throw new BizException(ErrorCode.PARAM_INVALID, "里程碑付款至少需要 2 期");
        }

        BigDecimal sum = ms.stream()
                .map(TaskCreateRequest.Milestone::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal budget = req.getBudgetMax();
        if (budget == null || sum.compareTo(budget) != 0) {
            // ⭐ 不配平意味着"发单方承诺的钱"与"分期给的钱"不一致 ——
            //    结算时必然有一方受损，属于必须在发单时就拦住的资损风险
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "各期里程碑金额之和（" + sum.toPlainString() + "）必须等于总预算（"
                            + (budget == null ? "未填" : budget.toPlainString()) + "）");
        }

        // Phase 1：里程碑 × 多中标 互斥（Q21）—— 拆单组合会与里程碑叠加出 O(n×m) 的结算矩阵
        if (!"SINGLE".equals(req.getAwardMode())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "里程碑付款暂只支持单选（SINGLE）；多选中标与里程碑的组合将在后续版本开放");
        }
    }

    // ⑤ 验收标准 -------------------------------------------------------

    private void checkAcceptanceItems(TaskCreateRequest req) {
        List<TaskCreateRequest.AcceptanceItem> items = req.getAcceptanceItems();
        if (items == null || items.isEmpty()) {
            // ⭐ 这是全系统最重要的一条校验：没有验收标准，发单方恶意驳回时
            //    仲裁专员没有任何客观依据，只能和稀泥 —— 接单方的利益完全靠人品
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "必须至少填写 1 项验收标准（它是验收与仲裁的唯一客观依据）");
        }
        for (int i = 0; i < items.size(); i++) {
            if (isBlank(items.get(i).getItemName())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "第 " + (i + 1) + " 项验收标准的名称不能为空");
            }
        }
    }

    // ------------------------------------------------------------------

    private static void requirePositive(BigDecimal v, String name) {
        if (v == null || v.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, name + "必须大于 0");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String formName(String form) {
        return switch (form == null ? "" : form) {
            case "ONLINE" -> "线上";
            case "OFFLINE" -> "线下";
            case "HYBRID" -> "线上+线下";
            default -> String.valueOf(form);
        };
    }

    private static String payName(String pay) {
        return switch (pay == null ? "" : pay) {
            case "FIXED" -> "一口价";
            case "BID" -> "竞拍报价";
            case "BY_QUANTITY" -> "按量计酬";
            case "BY_DAY" -> "按天计酬";
            case "MILESTONE" -> "里程碑付款";
            default -> String.valueOf(pay);
        };
    }
}
