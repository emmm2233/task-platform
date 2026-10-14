package com.dsh.platform.task;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.SmsCodeService;
import com.dsh.platform.task.dto.BidResult;
import com.dsh.platform.task.dto.BidSummary;
import com.dsh.platform.task.service.BidService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T14 · 报价（竞拍模式的接单方出价）。
 *
 * <p>五条规则，每条都对应一次真实的争议场景：
 * <ol>
 *   <li><b>一人一价</b> —— 同一人对同一任务只有一条有效报价（改价是覆盖，不是新增）；
 *       否则发单方会在同一人的多个价格里挑，等于给了接单方"占多个坑"的机会；</li>
 *   <li>⭐ <b>改价留痕</b> —— 每次改价写一条历史。争议时要能回答"他截止前到底报了多少"，
 *       而"占位低价、临截止猛涨"是最典型的恶意行为；</li>
 *   <li><b>暗标</b> —— 非发单方看不到别人的报价。看不到就没有"压价一分钱"的军备竞赛；</li>
 *   <li><b>撤回是终态</b> —— 撤回后不能再报，防止"反复撤回试探市场"；</li>
 *   <li>⭐ <b>低价只标记不拒绝</b> —— 明显低于预算的报价打标给发单方看，
 *       但⛔ 不能自动拒绝：低价可能是接单方真有本事，平台无权替他决定。</li>
 * </ol>
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BidServiceTest {

    private static final Random RANDOM = new Random();

    @Autowired private BidService bidService;
    @Autowired private AuthService authService;
    @Autowired private SmsCodeService smsCodeService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private Long publisherId;
    private Long bidderA;
    private Long bidderB;

    @BeforeAll
    void setUp() {
        var keys = redis.keys("sms:limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        publisherId = newUser("发单方");
        bidderA = newUser("报价者A");
        bidderB = newUser("报价者B");
    }

    private Long newUser(String nickname) {
        String phone = "138" + String.format("%08d", RANDOM.nextInt(100_000_000));
        String ip = "10." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(254);
        String code = smsCodeService.send(phone, SmsScene.REGISTER, ip, "dev-" + RANDOM.nextLong());
        return authService.register(phone, "Passw0rd!", nickname, code).getUserId();
    }

    /** 造一个 BIDDING 的竞拍任务，预算 3000–5000。 */
    private Long seedBiddingTask(int bidVisibleMode, LocalDateTime bidDeadline, int maxBids) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM task_category WHERE code = 'DEV'", Long.class);
        jdbc.update("""
                INSERT INTO task (task_no, publisher_id, category_id, category_path, task_form, pay_mode,
                                  title, description, grant_mode, award_mode, required_count,
                                  budget_min, budget_max, deadline, bid_deadline, status,
                                  audit_status, source_client, version)
                VALUES (?,?,?,'/','ONLINE','BID', ?,?, 'BID','SINGLE',1, 3000.00, 5000.00, ?, ?, 'BIDDING',
                        'PASSED','web-user',0)
                """,
                "B" + Long.toString(System.nanoTime(), 36), publisherId, categoryId,
                "报价测试", "验证报价规则", LocalDateTime.now().plusDays(30), bidDeadline);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Long seedBiddingTask() {
        return seedBiddingTask(1, LocalDateTime.now().plusDays(7), 20);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 一人一价：重复报价是【覆盖】而非新增，且每次改价留一条历史")
    void one_user_one_bid_and_modification_keeps_history() {
        Long taskId = seedBiddingTask();

        bidService.submit(taskId, bidderA, new BigDecimal("4200.00"), 7, "我做过类似的");
        Long bidId = jdbc.queryForObject(
                "SELECT id FROM task_bid WHERE task_id = ? AND bidder_id = ?", Long.class, taskId, bidderA);

        bidService.submit(taskId, bidderA, new BigDecimal("3900.00"), 6, "可以更快");

        // 只有一条报价（覆盖）
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_bid WHERE task_id = ? AND bidder_id = ?",
                Integer.class, taskId, bidderA)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT quote_amount FROM task_bid WHERE id = ?", BigDecimal.class, bidId))
                .isEqualByComparingTo("3900.00");

        // ⭐ 历史留痕：两条（首次 + 改价）
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_bid_history WHERE bid_id = ?", Integer.class, bidId))
                .as("每次改价都要留一条历史 —— 争议时要能回答「他截止前到底报了多少」")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("⭐ 暗标：非发单方看不到别人的报价，只看得到自己的")
    void blind_mode_hides_others_bids_from_non_publisher() {
        Long taskId = seedBiddingTask();
        bidService.submit(taskId, bidderA, new BigDecimal("4000.00"), 7, "A 的方案");
        bidService.submit(taskId, bidderB, new BigDecimal("3500.00"), 5, "B 的方案");

        List<BidSummary> seenByA = bidService.list(taskId, bidderA);
        assertThat(seenByA).as("接单方只能看到自己那条").hasSize(1);
        assertThat(seenByA.get(0).getBidderId()).isEqualTo(bidderA);

        List<BidSummary> seenByPublisher = bidService.list(taskId, publisherId);
        assertThat(seenByPublisher).as("发单方能看到全部").hasSize(2);
    }

    @Test
    @DisplayName("⭐ 撤回是终态：撤回后不能再报（防「反复撤回试探市场」）")
    void withdrawal_is_terminal() {
        Long taskId = seedBiddingTask();
        bidService.submit(taskId, bidderA, new BigDecimal("4000.00"), 7, "先报一个");
        bidService.withdraw(taskId, bidderA);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM task_bid WHERE task_id = ? AND bidder_id = ?",
                String.class, taskId, bidderA)).isEqualTo("WITHDRAWN");

        assertThatThrownBy(() -> bidService.submit(
                taskId, bidderA, new BigDecimal("3800.00"), 6, "再报一次"))
                .as("撤回后不可再报")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.BID_CLOSED.getCode());
    }

    @Test
    @DisplayName("⭐ 低价只标记不拒绝：低于预算下限 50% 打 LOW_PRICE，但报价照常生效")
    void low_price_is_flagged_not_rejected() {
        Long taskId = seedBiddingTask();   // 预算 3000–5000，下限 50% = 1500

        BidResult r = bidService.submit(taskId, bidderA, new BigDecimal("400.00"), 5, "我很便宜");

        assertThat(r.isAccepted()).as("⛔ 不能自动拒绝低价 —— 平台无权替发单方决定").isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT abnormal_flag FROM task_bid WHERE task_id = ? AND bidder_id = ?",
                String.class, taskId, bidderA)).isEqualTo("LOW_PRICE");
    }

    @Test
    @DisplayName("报价截止后不能再报（31003 BID_CLOSED）")
    void bid_after_deadline_is_rejected() {
        Long taskId = seedBiddingTask(1, LocalDateTime.now().minusMinutes(1), 20);

        assertThatThrownBy(() -> bidService.submit(
                taskId, bidderA, new BigDecimal("4000.00"), 7, "迟到了"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.BID_CLOSED.getCode());
    }

    @Test
    @DisplayName("发单方不能给自己的任务报价；非竞拍任务不接受报价")
    void publisher_cannot_bid_and_non_bidding_task_rejects() {
        Long taskId = seedBiddingTask();

        assertThatThrownBy(() -> bidService.submit(
                taskId, publisherId, new BigDecimal("4000.00"), 7, "自报"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_TASK_OWNER.getCode());

        // 把任务改成招募中（抢单模式）→ 不该接受报价
        jdbc.update("UPDATE task SET status = 'RECRUITING', grant_mode = 'GRAB' WHERE id = ?", taskId);
        assertThatThrownBy(() -> bidService.submit(
                taskId, bidderA, new BigDecimal("4000.00"), 7, "抢单任务报什么价"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TASK_STATUS_INVALID.getCode());
    }
}
