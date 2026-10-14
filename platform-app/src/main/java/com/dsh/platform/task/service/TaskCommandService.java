package com.dsh.platform.task.service;

import com.dsh.platform.task.dto.CategoryNode;
import com.dsh.platform.task.dto.TaskCreateRequest;
import com.dsh.platform.task.dto.TaskCreateResult;
import com.dsh.platform.task.validator.TaskTypeValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 任务创建。
 *
 * <p>流程：校验（{@link TaskTypeValidator}）→ 落 task → 落子表（验收标准 / 里程碑 / 地点 / 附件）。
 * <b>整体一个事务</b>：⛔ 不能出现"任务建了但验收标准没落"的半成品 ——
 * 那种任务在验收环节会让仲裁无从下手。
 *
 * <p>初始状态为 {@code DRAFT}：创建不等于发布。
 * 发布要走审核（T11），审核通过后进入托管 → 招募（T10 的状态机负责推进）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskCommandService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TaskTypeValidator validator;
    private final CategoryService categoryService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public TaskCreateResult create(TaskCreateRequest req, Long publisherId) {
        // ① 三维动态校验（五类规则）
        validator.validate(req);

        CategoryNode category = categoryService.byCode(req.getCategoryCode());
        String taskNo = nextTaskNo();
        LocalDateTime now = LocalDateTime.now(clock);

        // ② 主表
        // ⭐ 只插入【真正提供了值】的列，其余交给库的 DEFAULT。
        //    理由：本表大量列是 `NOT NULL DEFAULT x`，而 **DEFAULT 只在列被省略时生效** ——
        //    显式传 null 会直接报 1048（本任务先后踩到 required_count、expense_reimburse 两次）。
        //    逐个补默认值是治标；"null 就不插这一列"才根治这一整类问题。
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("task_no", taskNo);
        c.put("publisher_id", publisherId);
        c.put("category_id", category.getId());
        c.put("category_path", category.getPath());
        c.put("task_form", req.getTaskForm());
        c.put("pay_mode", req.getPayMode());
        c.put("title", req.getTitle());
        c.put("description", req.getDescription());
        c.put("grant_mode", req.getGrantMode());
        c.put("award_mode", req.getAwardMode());
        c.put("required_count", req.getRequiredCount());
        c.put("deadline", req.getDeadline());
        c.put("accept_days", req.getAcceptDays());
        c.put("source_client", "web-user");
        c.put("version", 0);
        c.put("status", "DRAFT");
        c.put("audit_status", "PENDING");
        // 可选字段：有值才带上
        putIfPresent(c, "deliverable_desc", req.getDeliverableDesc());
        putIfPresent(c, "price", req.getPrice());
        putIfPresent(c, "budget_min", req.getBudgetMin());
        putIfPresent(c, "budget_max", req.getBudgetMax());
        putIfPresent(c, "unit_price", req.getUnitPrice());
        putIfPresent(c, "quantity", req.getQuantity());
        putIfPresent(c, "unit", req.getUnit());
        putIfPresent(c, "day_rate", req.getDayRate());
        putIfPresent(c, "days", req.getDays());
        putIfPresent(c, "expense_reimburse", req.getExpenseReimburse());
        putIfPresent(c, "expense_cap", req.getExpenseCap());
        putIfPresent(c, "start_at", req.getStartAt());
        putIfPresent(c, "bid_deadline", req.getBidDeadline());
        putIfPresent(c, "modify_round_limit", req.getModifyRoundLimit());
        putIfPresent(c, "allow_partial_delivery", req.getAllowPartialDelivery());
        putIfPresent(c, "ip_ownership", req.getIpOwnership());
        putIfPresent(c, "min_credit_level", req.getMinCreditLevel());
        if (req.getServiceTimeSlots() != null && !req.getServiceTimeSlots().isEmpty()) {
            // ⚠️ service_time_slots 是 **JSON 列**，必须写合法 JSON 数组。
            //    写成逗号分隔的普通字符串会报
            //    "Invalid JSON text: The document root must not be followed by other values"。
            //    （同一张表里 tags / skills / commission_snapshot / invited_user_ids 也是 JSON 列。）
            c.put("service_time_slots", toJson(req.getServiceTimeSlots()));
        }
        putIfPresent(c, "created_at", now);
        putIfPresent(c, "updated_at", now);

        String cols = String.join(",", c.keySet());
        String marks = String.join(",", c.keySet().stream().map(k -> "?").toList());

        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO task (" + cols + ") VALUES (" + marks + ")",
                    Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            for (Object v : c.values()) {
                ps.setObject(i++, v);
            }
            return ps;
        }, kh);

        Long taskId = kh.getKey() == null ? null : kh.getKey().longValue();
        if (taskId == null) {
            throw new IllegalStateException("任务创建失败：未取到主键");
        }

        // ③ 验收标准（校验已保证 ≥1 项）
        List<TaskCreateRequest.AcceptanceItem> items = req.getAcceptanceItems();
        for (int idx = 0; idx < items.size(); idx++) {
            var it = items.get(idx);
            jdbc.update("""
                    INSERT INTO task_acceptance_item (task_id, seq, item_name, item_desc, required, weight, created_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, taskId, idx + 1, it.getItemName(), nz(it.getItemDesc()),
                    Boolean.FALSE.equals(it.getRequired()) ? 0 : 1,
                    it.getWeight() == null ? 1 : it.getWeight(), now);
        }

        // ④ 里程碑（仅 MILESTONE 付款方式；校验已保证 ≥2 且配平）
        if ("MILESTONE".equals(req.getPayMode()) && req.getMilestones() != null) {
            for (var m : req.getMilestones()) {
                jdbc.update("""
                        INSERT INTO task_milestone
                          (task_id, seq, name, amount, prepay_ratio, deliverable_desc, deadline, created_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, taskId, m.getSeq(), m.getName(), m.getAmount(),
                        m.getPrepayRatio() == null ? java.math.BigDecimal.ZERO : m.getPrepayRatio(),
                        nz(m.getDeliverableDesc()),
                        // 里程碑没单独设截止时间就继承任务截止时间（NOT NULL）
                        m.getDeadline() == null ? req.getDeadline() : m.getDeadline(), now);
            }
        }

        // ⑤ 地点（线下/混合；校验已保证齐全）
        if (req.getLocation() != null) {
            var loc = req.getLocation();
            jdbc.update("""
                    INSERT INTO task_location
                      (task_id, province, city, district, address, lng, lat, service_radius_km, need_checkin, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, taskId, nz(loc.getProvince()), nz(loc.getCity()), nz(loc.getDistrict()), nz(loc.getAddress()),
                    loc.getLng(), loc.getLat(),
                    // service_radius_km 是 NOT NULL 无默认值；未填视为"不设服务半径"
                    loc.getServiceRadiusKm() == null ? 0 : loc.getServiceRadiusKm(),
                    Boolean.TRUE.equals(loc.getNeedCheckin()) ? 1 : 0, now);
        }

        // ⑥ 附件（若有）
        if (req.getAttachments() != null) {
            for (var a : req.getAttachments()) {
                jdbc.update("""
                        INSERT INTO task_attachment
                          (task_id, file_name, file_key, file_size, content_type, scan_status, created_at)
                        VALUES (?,?,?,?,?,'PENDING',?)
                        """, taskId, nz(a.getFileName()), nz(a.getFileKey()), a.getFileSize(), nz(a.getContentType()), now);
            }
        }

        log.info("任务创建成功 taskId={} taskNo={} 分类={} 形态={} 付款={} 发单方={}",
                taskId, taskNo, req.getCategoryCode(), req.getTaskForm(), req.getPayMode(), publisherId);

        return TaskCreateResult.builder()
                .taskId(taskId)
                .taskNo(taskNo)
                .status("DRAFT")
                .build();
    }

    /**
     * null → 空串。
     *
     * <p>⚠️ 子表大量文本列是 {@code NOT NULL DEFAULT ''}，而 <b>DEFAULT 只在列被省略时生效</b> ——
     * 显式传 null 直接报 1048。子表列数少、又是"逐条插入"，动态跳过 null 反而更绕，
     * 所以这里统一归一成空串。（主表列多，用的是"只插有值的列"那套。）
     */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 把字符串列表序列化成 JSON 数组（service_time_slots 等 JSON 列必须写合法 JSON）。 */
    private static String toJson(java.util.List<String> list) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 JSON 列失败", e);
        }
    }

    /** 只在有值时加入 —— 见 insert 处的注释（NOT NULL DEFAULT 与显式 null 的坑）。 */
    private static void putIfPresent(Map<String, Object> target, String col, Object value) {
        if (value != null) {
            target.put(col, value);
        }
    }

    /** 任务号：T + yyyyMMdd + 5 位随机。唯一性由 uk 兜底（Phase 1 规模足够）。 */
    private String nextTaskNo() {
        return "T" + LocalDateTime.now(clock).atZone(ZoneOffset.ofHours(8)).format(DAY)
                + String.format("%05d", ThreadLocalRandom.current().nextInt(100000));
    }
}
