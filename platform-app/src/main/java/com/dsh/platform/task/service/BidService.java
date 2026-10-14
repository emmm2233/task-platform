package com.dsh.platform.task.service;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.task.dto.BidResult;
import com.dsh.platform.task.dto.BidSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 报价服务（竞拍模式的接单方出价）。
 *
 * <p>五条规则见测试类注释。这里只强调两条实现上的取舍：
 *
 * <p>⭐ <b>改价是覆盖 + 留痕，不是新增</b>：{@code task_bid} 上有
 * {@code uk_task_bidder(task_id, bidder_id)}，所以同一个人物理上只能有一条。
 * 每次改价往 {@code task_bid_history} 写一条 —— 那条历史在争议时是<b>唯一证据</b>：
 * "占位低价、临截止猛涨"这种恶意行为只能靠它抓出来。
 *
 * <p>⭐ <b>低价只标记不拒绝</b>：低于预算下限 50% 打 {@code LOW_PRICE} 给发单方看。
 * ⛔ 不能自动拒绝 —— 低价可能是接单方真有本事（模板化交付、想攒好评），
 * 平台替发单方拒绝等于替他做商业判断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BidService {

    /** 低于预算下限的这个比例即标记为异常低价。 */
    private static final BigDecimal LOW_PRICE_RATIO = new BigDecimal("0.5");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public BidResult submit(Long taskId, Long bidderId, BigDecimal quoteAmount,
                            Integer durationDays, String planDesc) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT publisher_id, status, grant_mode, budget_min, budget_max,
                       bid_deadline, deadline
                  FROM task WHERE id = ?
                """, taskId);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        Map<String, Object> t = rows.get(0);

        // ① 只有 BIDDING 状态接受报价 —— 抢单任务没有"报价"这个概念
        String status = String.valueOf(t.get("status"));
        if (!"BIDDING".equals(status)) {
            throw new BizException(ErrorCode.TASK_STATUS_INVALID,
                    "当前任务不接受报价（状态：" + status + "）");
        }
        // ② 截止校验：bid_deadline 优先，没设就用 deadline
        LocalDateTime deadline = toLdt(t.get("bid_deadline") != null ? t.get("bid_deadline") : t.get("deadline"));
        if (deadline != null && LocalDateTime.now(clock).isAfter(deadline)) {
            throw new BizException(ErrorCode.BID_CLOSED, "报价已于 " + deadline + " 截止");
        }
        // ③ 发单方不能给自己的任务报价
        if (String.valueOf(t.get("publisher_id")).equals(String.valueOf(bidderId))) {
            throw new BizException(ErrorCode.NOT_TASK_OWNER, "不能给自己的任务报价");
        }
        if (quoteAmount == null || quoteAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报价金额必须大于 0");
        }

        // ④ 已有报价？撤回是终态，不可再报；否则视为改价
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, status FROM task_bid WHERE task_id = ? AND bidder_id = ?", taskId, bidderId);
        boolean isUpdate = !existing.isEmpty();
        String abnormal = abnormalFlag(quoteAmount, (BigDecimal) t.get("budget_min"));

        if (isUpdate) {
            String prevStatus = String.valueOf(existing.get(0).get("status"));
            if ("WITHDRAWN".equals(prevStatus)) {
                throw new BizException(ErrorCode.BID_CLOSED, "你已撤回报价，不可再次报价");
            }
            if (!"QUOTED".equals(prevStatus)) {
                throw new BizException(ErrorCode.BID_CLOSED, "报价已被处理（" + prevStatus + "），不可修改");
            }
            Long bidId = ((Number) existing.get(0).get("id")).longValue();

            // ⭐ 顺序很关键，三步不能换：
            //    ① 先读出【改之前】的值（UPDATE 之后就再也读不到了）
            //    ② 执行 UPDATE（version +1）
            //    ③ 用「旧值 + 新版本号」写历史
            //    ⛔ 若把 ③ 放在 ② 之前，"新版本号"就还没产生，两次改价会算出同一个号
            //       而撞上 uk_bid_version（本用例第一版就是这么错的）。
            Map<String, Object> old = jdbc.queryForMap(
                    "SELECT quote_amount, duration_days, plan_desc, version FROM task_bid WHERE id = ?", bidId);
            jdbc.update("""
                    UPDATE task_bid
                       SET quote_amount = ?, duration_days = ?, plan_desc = ?, abnormal_flag = ?,
                           version = version + 1, updated_at = NOW(3)
                     WHERE id = ?
                    """, quoteAmount, durationDays, nz(planDesc), nz(abnormal), bidId);
            // 历史版本号 = 【变更后】的报价版本 + 1。
            // 报价 version 从 0 起：首次报价后 version=0 → 历史 1；首次改价后 version=1 → 历史 2。
            // ⛔ 用 old.version + 1 会与首次那条历史撞号（都是 1）。
            int newBidVersion = ((Number) old.get("version")).intValue() + 1;
            insertHistory(bidId, newBidVersion + 1,
                    old.get("quote_amount"), old.get("duration_days"), old.get("plan_desc"));
            return BidResult.builder().accepted(true).bidId(bidId).abnormalFlag(abnormal).updated(true).build();
        }

        jdbc.update("""
                INSERT INTO task_bid (task_id, bidder_id, quote_amount, duration_days, plan_desc,
                                      status, abnormal_flag, bidder_level, version, created_at, updated_at)
                VALUES (?,?,?,?,?, 'QUOTED', ?, (SELECT credit_level FROM sys_user WHERE id = ?), 0, NOW(3), NOW(3))
                """, taskId, bidderId, quoteAmount, durationDays, nz(planDesc), nz(abnormal), bidderId);
        Long bidId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        saveHistory(bidId, taskId, bidderId);

        // 报价数 +1（冗余列，同一事务内维护）
        jdbc.update("UPDATE task SET bid_count = bid_count + 1, updated_at = NOW(3) WHERE id = ?", taskId);

        return BidResult.builder().accepted(true).bidId(bidId).abnormalFlag(abnormal).updated(false).build();
    }

    /**
     * 报价列表。
     *
     * <p>⭐ 暗标过滤在服务层做：非发单方<b>只返回自己那条</b>。
     * ⛔ 不能"返回全部让前端不显示"—— 那样报价已经从接口泄露出去了。
     */
    public List<BidSummary> list(Long taskId, Long viewerId) {
        boolean isPublisher = isPublisher(taskId, viewerId);
        String sql = """
                SELECT b.id, b.bidder_id, b.quote_amount, b.duration_days, b.plan_desc,
                       b.status, b.abnormal_flag, b.bidder_level, b.created_at,
                       u.nickname AS bidder_nickname
                  FROM task_bid b
                  LEFT JOIN sys_user u ON u.id = b.bidder_id
                 WHERE b.task_id = ?
                """
                + (isPublisher ? "" : " AND b.bidder_id = ?")
                + " ORDER BY b.quote_amount ASC, b.id ASC";

        Object[] args = isPublisher ? new Object[]{taskId} : new Object[]{taskId, viewerId};
        return jdbc.query(sql, ROW_MAPPER, args);
    }

    /** 撤回：终态，撤回后不可再报。 */
    @Transactional
    public void withdraw(Long taskId, Long bidderId) {
        int n = jdbc.update("""
                UPDATE task_bid SET status = 'WITHDRAWN', updated_at = NOW(3)
                 WHERE task_id = ? AND bidder_id = ? AND status = 'QUOTED'
                """, taskId, bidderId);
        if (n == 0) {
            throw new BizException(ErrorCode.BID_CLOSED, "没有可撤回的报价（可能已撤回或已被选定）");
        }
        jdbc.update("UPDATE task SET bid_count = GREATEST(bid_count - 1, 0), updated_at = NOW(3) WHERE id = ?", taskId);
    }

    // ------------------------------------------------------------------

    /**
     * 首次报价后留一条历史（读当前值并 +1）。
     *
     * <p>版本号是<b>递增编号</b>而不是"当时的 version 值" ——
     * 后者在首次报价与首次改价时都是 0，会直接撞 {@code uk_bid_version}。
     */
    private void saveHistory(Long bidId, Long taskId, Long bidderId) {
        Map<String, Object> cur = jdbc.queryForMap(
                "SELECT quote_amount, duration_days, plan_desc, version FROM task_bid WHERE id = ?", bidId);
        insertHistory(bidId, ((Number) cur.get("version")).intValue() + 1,
                cur.get("quote_amount"), cur.get("duration_days"), cur.get("plan_desc"));
    }

    /** 显式给值地写一条历史（改价路径用：值是改之前的）。 */
    private void insertHistory(Long bidId, int version, Object quoteAmount,
                               Object durationDays, Object planDesc) {
        jdbc.update("""
                INSERT INTO task_bid_history (bid_id, version, quote_amount, duration_days, plan_desc, created_at)
                VALUES (?,?,?,?,?, NOW(3))
                """, bidId, version, quoteAmount, durationDays, planDesc);
    }

    /** 明显低于预算下限即标记 —— ⛔ 只标记，不拒绝。 */
    private String abnormalFlag(BigDecimal quote, BigDecimal budgetMin) {
        if (budgetMin == null || budgetMin.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return quote.compareTo(budgetMin.multiply(LOW_PRICE_RATIO)) < 0 ? "LOW_PRICE" : null;
    }

    private boolean isPublisher(Long taskId, Long viewerId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT publisher_id FROM task WHERE id = ?", Long.class, taskId);
        return !ids.isEmpty() && ids.get(0).equals(viewerId);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static LocalDateTime toLdt(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) o;
    }

    private static final RowMapper<BidSummary> ROW_MAPPER = (rs, i) -> {
        BidSummary s = new BidSummary();
        s.setBidId(rs.getLong("id"));
        s.setBidderId(rs.getLong("bidder_id"));
        s.setBidderNickname(rs.getString("bidder_nickname"));
        s.setBidderLevel(rs.getString("bidder_level"));
        s.setQuoteAmount(rs.getBigDecimal("quote_amount"));
        s.setDurationDays((Integer) rs.getObject("duration_days"));
        s.setPlanDesc(rs.getString("plan_desc"));
        s.setStatus(rs.getString("status"));
        s.setAbnormalFlag(rs.getString("abnormal_flag"));
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        s.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return s;
    };
}