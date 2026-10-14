package com.dsh.platform.task.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.task.dto.CategoryNode;
import com.dsh.platform.task.entity.TaskCategory;
import com.dsh.platform.task.mapper.TaskCategoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类服务。
 *
 * <p>⭐ 分类不是"贴个标签"—— 它是<b>任务创建的约束来源</b>：
 * {@code allowForms} 决定能不能选线下、{@code allowPayModes} 决定能不能竞拍、
 * {@code auditLevel} 决定走机审还是人审、{@code commissionRate} 决定费率覆盖。
 * 所以 T9 的任务创建校验必须调本服务，而不是在业务代码里散写判断。
 *
 * <p>⛔ 只读接口不做缓存：分类只有 46 行、变更极低频，
 * 而"运营下架分类后前台还看得到"是明显的问题（本测试专门用例覆盖了这点）。
 * 真成为瓶颈时再加带失效广播的缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final TaskCategoryMapper mapper;

    /** 一级分类（树顶）。 */
    public List<CategoryNode> tree(int maxLevel) {
        List<TaskCategory> roots = mapper.selectByLevel(1);
        List<CategoryNode> nodes = new ArrayList<>(roots.size());
        for (TaskCategory root : roots) {
            CategoryNode node = toNode(root);
            if (maxLevel >= 2) {
                node.setChildren(children(root.getId()));
            }
            nodes.add(node);
        }
        return nodes;
    }

    /** 某个分类的直接子分类。 */
    public List<CategoryNode> children(Long parentId) {
        return mapper.selectChildren(parentId).stream().map(this::toNode).toList();
    }

    /**
     * 按 code 取分类。
     *
     * <p>⛔ 查不到时抛业务异常而不是返回 null ——
     * 调用方（任务创建）拿到 null 会 NPE 或静默放行校验，两者都比明确报错糟。
     */
    public CategoryNode byCode(String code) {
        TaskCategory c = mapper.selectByCode(code);
        if (c == null || !Boolean.TRUE.equals(c.getEnabled())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "分类不存在或已停用：" + code);
        }
        return toNode(c);
    }

    /**
     * 取分类及其全部祖先的 code（从根到当前）。
     *
     * <p>用途：费率与审核级别需要<b>沿路径向上回退</b> ——
     * 二级分类没配就用一级的，一级没配就用通用规则。
     */
    public List<String> pathCodes(Long categoryId) {
        Map<String, String> ordered = new LinkedHashMap<>();
        TaskCategory cur = mapper.selectById(categoryId);
        int guard = 0;
        while (cur != null && guard++ < 8) {          // 防御性上限，避免脏数据成环导致死循环
            ordered.put(cur.getCode(), cur.getCode());
            if (cur.getParentId() == null || cur.getParentId() == 0L) {
                break;
            }
            cur = mapper.selectById(cur.getParentId());
        }
        List<String> out = new ArrayList<>(ordered.keySet());
        java.util.Collections.reverse(out);          // 根在前
        return out;
    }

    // ------------------------------------------------------------------

    private CategoryNode toNode(TaskCategory c) {
        CategoryNode n = new CategoryNode();
        n.setId(c.getId());
        n.setParentId(c.getParentId());
        n.setPath(c.getPath());
        n.setLevel(c.getLevel());
        n.setCode(c.getCode());
        n.setName(c.getName());
        n.setIcon(c.getIcon());
        n.setAllowForms(split(c.getAllowForms()));
        n.setAllowPayModes(split(c.getAllowPayModes()));
        n.setCommissionRate(c.getCommissionRate());
        n.setRequireLicense(c.getRequireLicense());
        n.setAuditLevel(c.getAuditLevel());
        n.setNeedCheckinDefault(c.getNeedCheckinDefault());
        n.setSortNo(c.getSortNo());
        return n;
    }

    /** {@code "ONLINE,OFFLINE"} → {@code ["ONLINE", "OFFLINE"]}；空值返回空列表。 */
    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
