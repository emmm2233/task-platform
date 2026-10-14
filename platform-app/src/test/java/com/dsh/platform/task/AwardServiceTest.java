package com.dsh.platform.task;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.SmsCodeService;
import com.dsh.platform.task.dto.AwardResult;
import com.dsh.platform.task.service.AwardService;
import com.dsh.platform.task.statemachine.TaskStatus;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T15 · 中标（M3 收官）。
 *
 * <p>四件事必须守住：
 * <ol>
 *   <li><b>事务 + 行锁</b> —— {@code SELECT ... FOR UPDATE} 锁住任务行，
 *       两个并发中标请求只有一个能成功，另一个会看到已变更的状态；</li>
 *   <li>⭐ <b>幂等键</b> = {@code taskId + awardMode + 排序后 bidIds 的哈希}。
 *       用<b>排序后</b>的 id 列表是刻意的：同样一组中标方，
 *       选择顺序不同不该产生两笔订单；</li>
 *   <li>⭐ <b>超发拦截</b> —— {@code Σ中标金额 + 佣金 ≤ 托管额}。
 *       这是资损防线：多给了就有一方拿不到钱；</li>
 *   <li><b>落败报价置 REJECTED</b> —— 不置的话接单方永远不知道自己输了，
 *       会一直等一个不会来的中标通知。</li>
 * </ol>
 *
 * <p>⚠️ 职责边界：本任务<b>不创建</b> {@code sub_order} 与 {@code escrow} ——
 * 两者的 {@code order_id} 都非空，而 {@code biz_order} 是资金视角的订单、
 * 在托管阶段（T17）才产生。在 T15 硬造没有 order_id 的子订单只会让资金链路对不上账。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwardServiceTest {

    private static final Random RANDOM = new Random();

    @Autowired private AwardService awardService;
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

    /** 造一个 PENDING_SELECT 的任务 + 两条报价。返回 [taskId, bidIdA, bidIdB]。 */
    private Long[] seedPendingSelect() {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM task_category WHERE code = 'DEV'", Long.class);
        jdbc.update("""
                INSERT INTO task (task_no, publisher_id, category_id, category_path, task_form, pay_mode,
                                  title, description, grant_mode, award_mode, required_count,
                                  budget_min, budget_max, deadline, status, audit_status,
                                  escrow_amount, source_client, version)
                VALUES (?,?,?,'/','ONLINE','BID', ?,?, 'BID','SINGLE',1, 3000.00, 5000.00, ?,
                        'PENDING_SELECT','PASSED', 6000.00, 'web-user',0)
                """,
                "A" + Long.toString(System.nanoTime(), 36), publisherId, categoryId,
                "中标测试", "验证中标规则", LocalDateTime.now().plusDays(20));
        Long taskId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Long bidA = insertBid(taskId, bidderA, "4000.00");
        Long bidB = insertBid(taskId, bidderB, "3500.00");
        jdbc.update("UPDATE task SET bid_count = 2 WHERE id = ?", taskId);
        return new Long[]{taskId, bidA, bidB};
    }

    private Long insertBid(Long taskId, Long bidderId, String amount) {
        jdbc.update("""
                INSERT INTO task_bid (task_id, bidder_id, quote_amount, duration_days, plan_desc,
                                      status, abnormal_flag, bidder_level, version, created_at, updated_at)
                VALUES (?,?,?,7,'方案','QUOTED','','NORMAL',0,NOW(3),NOW(3))
                """, taskId, bidderId, new BigDecimal(amount));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String statusOf(Long taskId) {
        return jdbc.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId);
    }

    private String bidStatus(Long bidId) {
        return jdbc.queryForObject("SELECT status FROM task_bid WHERE id = ?", String.class, bidId);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 中标：写 award + 任务转 PENDING_START + 中标的 ACCEPTED、落败的 REJECTED")
    void award_writes_record_and_updates_bid_statuses() {
        Long[] s = seedPendingSelect();
        Long taskId = s[0];

        AwardResult r = awardService.award(taskId, publisherId, List.of(s[1]));

        assertThat(r.getAwardIds()).hasSize(1);
        assertThat(statusOf(taskId)).isEqualTo(TaskStatus.PENDING_START.name());
        assertThat(bidStatus(s[1])).isEqualTo("ACCEPTED");
        // ⭐ 落败的必须置 REJECTED —— 否则接单方会一直等一个不会来的中标通知
        assertThat(bidStatus(s[2])).isEqualTo("REJECTED");

        var award = jdbc.queryForMap("SELECT * FROM award WHERE task_id = ?", taskId);
        // ⚠️ queryForMap 对 BIGINT 可能返回 Integer，直接 isEqualTo(Long) 会报
        //    "expected: 10848L but was: 10848" —— 数值相同、类型不同。
        //    ⛔ 断言里比较数据库返回值时必须按 Number 归一，不能依赖装箱类型。
        assertThat(((Number) award.get("awardee_id")).longValue()).isEqualTo(bidderA);
        assertThat((BigDecimal) award.get("awarded_amount")).isEqualByComparingTo("4000.00");
        assertThat(award.get("award_mode")).isEqualTo("SINGLE");
    }

    @Test
    @DisplayName("⭐ 幂等：同一组中标方无论顺序如何，重复提交都只产生一条 award")
    void award_is_idempotent_regardless_of_bid_order() {
        Long[] s = seedPendingSelect();
        Long taskId = s[0];

        AwardResult first = awardService.award(taskId, publisherId, List.of(s[1]));
        // 用同一个 key 再调一次（第一次已把状态推到 PENDING_START，正常路径会拒；
        // 幂等要求它返回首次结果而不是报错）
        AwardResult second = awardService.award(taskId, publisherId, List.of(s[1]));

        assertThat(second.getAwardIds()).isEqualTo(first.getAwardIds());
        assertThat(second.getIdempotentKey()).isEqualTo(first.getIdempotentKey());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM award WHERE task_id = ?", Integer.class, taskId))
                .as("幂等键必须挡住重复写 award")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 幂等键与【顺序无关】：bidIds 顺序不同应算出同一个键")
    void idempotent_key_is_order_insensitive() {
        Long[] s = seedPendingSelect();
        String k1 = awardService.idempotentKey(s[0], "MULTI", List.of(s[1], s[2]));
        String k2 = awardService.idempotentKey(s[0], "MULTI", List.of(s[2], s[1]));

        assertThat(k1).as("同样一组中标方，选择顺序不同不该产生两笔订单").isEqualTo(k2);
        assertThat(awardService.idempotentKey(s[0], "MULTI", List.of(s[1])))
                .as("不同组合必须算出不同的键").isNotEqualTo(k1);
    }

    @Test
    @DisplayName("⭐ 并发中标同一任务：只有一个成功（FOR UPDATE 锁任务行）")
    void concurrent_award_yields_one_success() throws Exception {
        Long[] s = seedPendingSelect();
        Long taskId = s[0];

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            final Long bidId = s[1 + i];
            pool.submit(() -> {
                try {
                    start.await();
                    awardService.award(taskId, publisherId, List.of(bidId));
                    ok.incrementAndGet();
                } catch (Exception ignored) {
                    // 第二个请求应因状态已变更（或幂等键不同）而失败
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(ok.get()).as("并发中标只能有一个成功").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM award WHERE task_id = ?", Integer.class, taskId)).isEqualTo(1);
        assertThat(statusOf(taskId)).isEqualTo(TaskStatus.PENDING_START.name());
    }

    @Test
    @DisplayName("⭐ 超发拦截：Σ中标金额 + 佣金 > 托管额 必须被拒（资损防线）")
    void over_issue_is_rejected() {
        // 纯函数直测：不做落库，规则本身与 escrow 是否存在无关
        assertThat(awardService.checkNoOverIssue(
                new BigDecimal("10000.00"), new BigDecimal("9000.00"), new BigDecimal("1000.00")))
                .as("9000 + 1000 = 10000，正好等于托管额，应放行")
                .isTrue();

        assertThat(awardService.checkNoOverIssue(
                new BigDecimal("10000.00"), new BigDecimal("9000.01"), new BigDecimal("1000.00")))
                .as("超出 0.01 也必须拦 —— 资损没有「差不多」")
                .isFalse();

        assertThat(awardService.checkNoOverIssue(
                new BigDecimal("10000.00"), new BigDecimal("9000.00"), new BigDecimal("1000.01")))
                .isFalse();
    }

    @Test
    @DisplayName("只有 PENDING_SELECT / BIDDING 可中标；重复中标与非法中标都被拒")
    void only_selectable_tasks_can_be_awarded() {
        Long[] s = seedPendingSelect();
        Long taskId = s[0];

        // 草稿任务不能中标
        jdbc.update("UPDATE task SET status = 'DRAFT' WHERE id = ?", taskId);
        assertThatThrownBy(() -> awardService.award(taskId, publisherId, List.of(s[1])))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TASK_STATUS_INVALID.getCode());

        // 非发单方不能决定中标
        jdbc.update("UPDATE task SET status = 'PENDING_SELECT' WHERE id = ?", taskId);
        assertThatThrownBy(() -> awardService.award(taskId, bidderB, List.of(s[1])))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_TASK_OWNER.getCode());
    }
}
