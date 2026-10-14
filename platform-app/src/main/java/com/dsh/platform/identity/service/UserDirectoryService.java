package com.dsh.platform.identity.service;

import com.dsh.platform.identity.entity.SysUser;
import com.dsh.platform.identity.mapper.SysUserMapper;
import com.dsh.platform.identity.security.DataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户目录查询 —— 数据范围（第三层鉴权）的落点。
 *
 * <p>⭐ 本类的存在意义：把"这条记录该不该被看见"收敛到<b>一处</b>。
 * 若让每个 Service 自己写 {@code if (row.getUserId().equals(me))}，
 * 几十个查询里迟早漏一个 —— 漏掉的那一个就是数据泄露。
 *
 * <p>⚠️ 当前实现是<b>按 id 取单条</b>时的判定。列表查询的范围过滤
 * （例如"我发布的任务"、"与我有关的订单"）在各自模块里通过
 * {@link DataScope#canSee} 或等价的 SQL 条件实现，等 T12/T23 有了真实列表查询
 * 再统一收进 MyBatis 拦截器 —— 那时才有足够信息判断"哪些表、按哪些列过滤"。
 */
@Service
@RequiredArgsConstructor
public class UserDirectoryService {

    private final SysUserMapper userMapper;

    /**
     * 按数据范围取用户。
     *
     * @param targetId  目标用户
     * @param principal 当前请求主体
     * @return 看得见则返回用户；看不见返回 {@code null}（⛔ 不是抛异常 —— 见下）
     */
    public SysUser findVisible(Long targetId, DataScope.Principal principal) {
        // ⛔ 这里返回 null 而不是抛 403，是刻意的：
        //    对"存在但你看不见"与"根本不存在"给出不同响应，等于提供了
        //    "这个 id 存不存在"的探测器。上层统一按 404 处理。
        if (!DataScope.canSee(principal, targetId)) {
            return null;
        }
        return userMapper.selectById(targetId);
    }
}
