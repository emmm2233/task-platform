package com.dsh.platform.task;

import com.dsh.platform.identity.dto.LoginResult;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.SmsCodeService;
import com.dsh.platform.task.dto.GrabResult;
import com.dsh.platform.task.service.GrabService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13 · 抢单（并发唯一性）。
 *
 * <p>⭐ <b>唯一性只能由数据库判定</b>：条件 {@code UPDATE} 的<b>影响行数</b>。
 * 本测试用 N 个用户并发抢同一单，断言<b>成功数恒为 1</b> ——
 * 这是整个 P1 里最不能出错的一条（错一次就是两个人被派同一单）。
 *
 * <p>⛔ 反面做法（都会在并发下失效）：
 * <ul>
 *   <li>"先 SELECT 看有没有人接，再 UPDATE" —— 两个请求都 SELECT 到空，然后都 UPDATE；</li>
 *   <li>用 Redis 锁当唯一性依据 —— 锁过期、Redis 抖动、多实例时钟漂移都会漏；
 *       锁只能<b>削峰</b>，不能作为正确性依据。</li>
 * </ul>
 *
 * <p>⚠️ 规模说明：计划里写的是 5000 并发。本用例默认跑 <b>500</b> 并发 ——
 * 唯一性是<b>布尔性质</b>，500 并发已足以暴露"先查后改"的缺陷，而 5000 规模的
 * 吞吐/延迟验证属于 T40 的压测套件（那里用 k6 打真实 HTTP 才有意义）。
 * 想跑满规模：{@code -Dgrab.concurrency=5000}。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrabConcurrencyTest {

    private static final Random RANDOM = new Random();

    /** 并发规模：可用系统属性覆盖。 */
    private static final int CONCURRENCY =
            Integer.getInteger("grab.concurrency", 500);

    private static final int THREADS = 200;

    @Autowired private GrabService grabService;
    @Autowired private AuthService authService;
    @Autowired private SmsCodeService smsCodeService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private Long publisherId;
    private final List<Long> takers = new ArrayList<>();

    @BeforeAll
    void setUp() {
        var keys = redis.keys("sms:limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        String phone = "138" + String.format("%08d", RANDOM.nextInt(100_000_000));
        String ip = "10." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(254);
        String code = smsCodeService.send(phone, SmsScene.REGISTER, ip, "dev-" + RANDOM.nextLong());
        publisherId = authService.register(phone, "Passw0rd!", "发单方", code).getUserId();

        // 直接批量造抢单者：⛔ 不走注册流程（那要发 N 条短信、跑 N 次 BCrypt，纯浪费）
        StringBuilder sb = new StringBuilder(
                "INSERT INTO sys_user (user_no, identity, phone, phone_hash, password_hash, nickname,"
                        + " real_name_status, credit_score, credit_level, status, permission_version,"
                        + " source_client, version, created_at, updated_at) VALUES ");
        long seed = System.nanoTime();
        for (int i = 0; i < CONCURRENCY; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("('G").append(seed % 100000000L).append(i).append("','USER','x','h")
                    .append(seed % 100000000L).append(i).append("','p','抢单者").append(i)
                    .append("','PASSED',60,'NORMAL','NORMAL',1,'web-user',0,NOW(3),NOW(3))");
        }
        jdbc.update(sb.toString());

        takers.addAll(jdbc.queryForList(
                "SELECT id FROM sys_user WHERE nickname LIKE '抢单者%' ORDER BY id DESC LIMIT ?",
                Long.class, CONCURRENCY));
        assertThat(takers).hasSize(CONCURRENCY);
    }

    /** 造一个 RECRUITING 的抢单任务。 */
    private Long seedGrabTask() {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM task_category WHERE code = 'DEV'", Long.class);
        jdbc.update("""
                INSERT INTO task (task_no, publisher_id, category_id, category_path, task_form, pay_mode,
                                  title, description, grant_mode, award_mode, required_count,
                                  price, deadline, status, audit_status, source_client, version)
                VALUES (?,?,?,'/','ONLINE','FIXED', ?,?,'GRAB','SINGLE',1, ?,?, 'RECRUITING','PASSED','web-user',0)
                """,
                "G" + System.nanoTime() % 100000000L, publisherId, categoryId,
                "并发抢单测试", "验证唯一性", new BigDecimal("1000.00"),
                LocalDateTime.now().plusDays(7));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐⭐ 并发抢同一单：成功数恒为 1，其余全部失败且原因可分辨")
    void concurrent_grabs_yield_exactly_one_winner() throws Exception {
        Long taskId = seedGrabTask();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<String> failCodes = java.util.Collections.synchronizedList(new ArrayList<>());

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < CONCURRENCY; i++) {
            final Long taker = takers.get(i);
            pool.submit(() -> {
                try {
                    start.await();
                    GrabResult r = grabService.grab(taskId, taker, java.util.UUID.randomUUID().toString());
                    if (r.isSuccess()) {
                        success.incrementAndGet();
                    } else {
                        failCodes.add(r.getFailCode() == null ? "NULL" : String.valueOf(r.getFailCode()));
                    }
                } catch (Exception e) {
                    failCodes.add("EX:" + e.getClass().getSimpleName());
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(180, TimeUnit.SECONDS)).isTrue();
        long cost = System.currentTimeMillis() - t0;

        // ⭐⭐ 唯一性：只能有一个赢家
        assertThat(success.get())
                .as("%d 并发抢同一单，成功数必须恰好为 1（耗时 %d ms）", CONCURRENCY, cost)
                .isEqualTo(1);

        // 任务确实被指派了，且状态推进到 PENDING_START
        assertThat(jdbc.queryForObject(
                "SELECT assignee_id FROM task WHERE id = ?", Long.class, taskId)).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM task WHERE id = ?", String.class, taskId))
                .isEqualTo(TaskStatus.PENDING_START.name());

        // 没有"未预期异常"—— 全部失败都应是可解释的业务失败
        assertThat(failCodes).as("失败原因里不应出现未知异常").noneMatch(c -> c.startsWith("EX:"));
        // 至少有一条抢单日志落库（成功那条）
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_grab_log WHERE task_id = ?", Integer.class, taskId))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 抢占同一个 requestId 幂等：重复提交只生效一次")
    void same_request_id_is_idempotent() {
        Long taskId = seedGrabTask();
        String rid = java.util.UUID.randomUUID().toString();
        Long taker = takers.get(0);

        GrabResult first = grabService.grab(taskId, taker, rid);
        GrabResult second = grabService.grab(taskId, taker, rid);

        assertThat(first.isSuccess()).isTrue();
        assertThat(second.isSuccess()).as("同 requestId 应返回首次结果").isTrue();
        assertThat(second.getSubOrderId()).isEqualTo(first.getSubOrderId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_grab_log WHERE task_id = ? AND request_id = ?",
                Integer.class, taskId, rid)).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 失败必须给出可操作的原因，不是通用错误")
    void grab_failure_tells_the_user_what_to_do_next() {
        Long taskId = seedGrabTask();
        GrabResult win = grabService.grab(taskId, takers.get(0), java.util.UUID.randomUUID().toString());
        assertThat(win.isSuccess()).isTrue();

        GrabResult lose = grabService.grab(taskId, takers.get(1), java.util.UUID.randomUUID().toString());
        assertThat(lose.isSuccess()).isFalse();
        // 31002 = GRAB_TAKEN。⛔ 不能是 10999 那种"系统繁忙"
        assertThat(lose.getFailCode()).isEqualTo(31002);
        assertThat(lose.getFailReason()).isNotBlank();
        // ⭐ 被抢走时要给相似任务，让用户能立刻做下一步而不是重试同一单
        assertThat(lose.getSimilarTasks()).as("被抢走时应给出相似任务").isNotNull();
    }

    @Test
    @DisplayName("⭐ 不满足条件时给出【不同】的错误码（区别于被抢走）")
    void gate_failures_use_distinct_codes() {
        Long taskId = seedGrabTask();

        // ① 发单方不能抢自己的单
        GrabResult self = grabService.grab(taskId, publisherId, java.util.UUID.randomUUID().toString());
        assertThat(self.isSuccess()).isFalse();
        assertThat(self.getFailCode()).isEqualTo(30003);   // NOT_TASK_OWNER

        // ② 信誉分不足（低于门槛 60）
        Long lowCredit = takers.get(2);
        jdbc.update("UPDATE sys_user SET credit_score = 30 WHERE id = ?", lowCredit);
        GrabResult low = grabService.grab(taskId, lowCredit, java.util.UUID.randomUUID().toString());
        assertThat(low.isSuccess()).isFalse();
        assertThat(low.getFailCode()).as("信誉分不足应是 70004，而不是 31002").isEqualTo(70004);
        assertThat(low.getFailReason()).contains("信誉分");
    }
}
