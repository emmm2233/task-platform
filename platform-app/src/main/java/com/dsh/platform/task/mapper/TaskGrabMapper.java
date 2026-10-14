package com.dsh.platform.task.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 抢单的唯一判定语句。
 *
 * <p>⭐⭐ <b>整个抢单正确性都压在这一条 SQL 上</b>。
 * 条件 {@code UPDATE} 在数据库层面是原子的：所有并发请求排成队，
 * 只有第一个满足 {@code WHERE} 的能把行改掉并返回影响行数 1，其余返回 0。
 *
 * <p>⛔ 绝不能改写成"先 SELECT 判断、再 UPDATE"：
 * 两个请求会同时 SELECT 到 {@code assignee_id IS NULL}，然后都去 UPDATE，
 * 结果两人都被派单 —— 而这个 bug 在单机串行测试里<b>永远不会出现</b>。
 *
 * <p>⛔ 也绝不能靠 Redis 锁来保证唯一性：锁会过期、Redis 会抖动、
 * 多实例时钟会漂移。锁只用来<b>削峰</b>（挡住绝大部分无效请求，减少数据库压力），
 * 正确性必须由这条 SQL 自己保证。
 */
@Mapper
public interface TaskGrabMapper {

    /**
     * 原子抢占。返回 1 表示抢到，返回 0 表示没抢到（已被别人抢走或状态不符）。
     */
    @Update("""
            UPDATE task
               SET status = 'PENDING_START',
                   assignee_id = #{userId},
                   award_at = NOW(3),
                   version = version + 1,
                   updated_at = NOW(3)
             WHERE id = #{taskId}
               AND status = 'RECRUITING'
               AND grant_mode = 'GRAB'
               AND assignee_id IS NULL
            """)
    int grabAtomic(@Param("taskId") Long taskId, @Param("userId") Long userId);

    /** 抢占失败后回读真实原因（是"被别人抢走"还是"状态本就不对"）。 */
    @Select("SELECT status FROM task WHERE id = #{taskId}")
    String currentStatus(@Param("taskId") Long taskId);

    @Select("SELECT assignee_id FROM task WHERE id = #{taskId}")
    Long currentAssignee(@Param("taskId") Long taskId);

    /** 同一 requestId 的首次结果（幂等）。 */
    @Select("""
            SELECT success FROM task_grab_log
             WHERE task_id = #{taskId} AND user_id = #{userId} AND request_id = #{requestId}
             LIMIT 1
            """)
    Boolean findIdempotentResult(@Param("taskId") Long taskId,
                                 @Param("userId") Long userId,
                                 @Param("requestId") String requestId);

    /** 失败时的相似任务推荐：同分类、预算相近、仍在招募中。 */
    @Select("""
            SELECT id FROM task
             WHERE status = 'RECRUITING' AND grant_mode = 'GRAB' AND assignee_id IS NULL
               AND category_id = (SELECT category_id FROM task WHERE id = #{taskId})
               AND id <> #{taskId}
             ORDER BY ABS(IFNULL(price, 0) - IFNULL((SELECT price FROM task WHERE id = #{taskId}), 0))
             LIMIT 3
            """)
    List<Long> similarTasks(@Param("taskId") Long taskId);
}