package com.dsh.platform.task;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.common.page.CursorCodec;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.SmsCodeService;
import com.dsh.platform.task.dto.HallQuery;
import com.dsh.platform.task.dto.TaskSummary;
import com.dsh.platform.task.service.TaskQueryService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T12 · 任务大厅检索与游标分页。
 *
 * <p>⭐ 本任务的核心不是"能翻页"，而是<b>不重不漏</b>。
 * 分页重复或漏项是最容易被忽略、又最难被用户说清的问题 ——
 * "我明明看到过那条任务，再翻就没了"，没有任何报错、日志也查不出。
 *
 * <p>⛔ 为什么不用 offset 分页：大厅按发布时间倒序，而任务随时在被发布与中标。
 * 用 {@code LIMIT 10 OFFSET 20} 时，若在你翻到第 3 页之前有 2 条新任务插到前面，
 * 第 3 页就会<b>重复</b>显示第 2 页末尾的 2 条。游标（键集）分页没有这个问题。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskQueryTest {

    private static final Random RANDOM = new Random();

    @Autowired private TaskQueryService queryService;
    @Autowired private AuthService authService;
    @Autowired private SmsCodeService smsCodeService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private Long publisherId;

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
    }

    /** 直接插任务行，绕过创建校验 —— 本任务只测检索。 */
    private Long seedTask(String status, String categoryCode, String title, BigDecimal price) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM task_category WHERE code = ?", Long.class, categoryCode);
        jdbc.update("""
                INSERT INTO task (task_no, publisher_id, category_id, category_path, task_form, pay_mode,
                                  title, description, grant_mode, award_mode, required_count,
                                  price, deadline, status, audit_status, source_client, version)
                VALUES (?,?,?,'/','ONLINE','FIXED', ?,?,'GRAB','SINGLE',1, ?,?,?,'PASSED','web-user',0)
                """,
                "T" + Long.toString(System.nanoTime(), 36), publisherId, categoryId,
                title, "检索测试用", price, LocalDateTime.now().plusDays(10), status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    // ------------------------------------------------------------------
    // ① 游标编解码
    // ------------------------------------------------------------------

    @Test
    @DisplayName("游标可往返：编码后解码得到相同的排序键与 id")
    void cursor_round_trips() {
        String c = CursorCodec.encode("2026-10-15T10:00:00", 12345L, "fp-abc");
        CursorCodec.Cursor decoded = CursorCodec.decode(c);

        assertThat(decoded.sortKey()).isEqualTo("2026-10-15T10:00:00");
        assertThat(decoded.id()).isEqualTo(12345L);
        assertThat(decoded.fingerprint()).isEqualTo("fp-abc");
    }

    @Test
    @DisplayName("非法游标给出参数错误，而不是 500")
    void malformed_cursor_is_rejected_as_param_error() {
        for (String bad : List.of("not-base64!!", "YWJj", "", "MTIz")) {
            assertThatThrownBy(() -> CursorCodec.decode(bad))
                    .as("非法游标：%s", bad)
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        }
    }

    @Test
    @DisplayName("⭐ 游标内嵌筛选指纹：换筛选条件后复用旧游标必须被拒（否则会取到不连续的页）")
    void cursor_rejects_fingerprint_mismatch() {
        String c = CursorCodec.encode("2026-10-15T10:00:00", 1L, "fp-A");
        assertThatThrownBy(() -> CursorCodec.decode(c, "fp-B"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        // 指纹一致时正常解出
        assertThat(CursorCodec.decode(c, "fp-A").id()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // ② ⭐ 不重不漏
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 翻完所有页：一条不漏、一条不重")
    void cursor_paging_returns_every_task_exactly_once() {
        String titlePrefix = "T12-PAGING-" + RANDOM.nextInt(1_000_000) + "-";
        int total = 37;
        for (int i = 0; i < total; i++) {
            seedTask("RECRUITING", "DEV", titlePrefix + i, new BigDecimal("1000.00"));
        }

        HallQuery q = new HallQuery();
        q.setKeyword(titlePrefix);
        q.setPageSize(10);

        Set<Long> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            q.setCursor(cursor);
            var page = queryService.hall(q);
            for (TaskSummary t : page.getList()) {
                assertThat(seen.add(t.getTaskId()))
                        .as("任务 %s 被返回了两次 —— 分页重复", t.getTaskId())
                        .isTrue();
            }
            pages++;
            cursor = page.getCursor();
            if (cursor == null) {
                break;
            }
            assertThat(pages).isLessThan(20);   // 防御：游标不前进会导致死循环
        }

        assertThat(seen).as("漏掉了任务").hasSize(total);
        assertThat(pages).isEqualTo(4);          // 10 + 10 + 10 + 7
    }

    @Test
    @DisplayName("页大小被限制在合理区间（防止一次拉全表）")
    void page_size_is_clamped() {
        HallQuery q = new HallQuery();
        q.setPageSize(100000);
        assertThat(queryService.hall(q).getList().size())
                .as("超大 pageSize 应被钳制")
                .isLessThanOrEqualTo(50);

        HallQuery q2 = new HallQuery();
        q2.setPageSize(0);
        assertThat(queryService.hall(q2).getList()).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // ③ 只返回招募中的任务
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 大厅只返回 RECRUITING 与 BIDDING，草稿/已结算/已取消一律不出现")
    void hall_only_returns_recruiting_and_bidding() {
        String prefix = "T12-STATUS-" + RANDOM.nextInt(1_000_000) + "-";
        Long recruiting = seedTask("RECRUITING", "DEV", prefix + "a", new BigDecimal("100.00"));
        Long bidding = seedTask("BIDDING", "DEV", prefix + "b", new BigDecimal("100.00"));
        seedTask("DRAFT", "DEV", prefix + "c", new BigDecimal("100.00"));
        seedTask("SETTLED", "DEV", prefix + "d", new BigDecimal("100.00"));
        seedTask("CANCELLED", "DEV", prefix + "e", new BigDecimal("100.00"));
        seedTask("PENDING_AUDIT", "DEV", prefix + "f", new BigDecimal("100.00"));

        HallQuery q = new HallQuery();
        q.setKeyword(prefix);
        List<Long> ids = queryService.hall(q).getList().stream().map(TaskSummary::getTaskId).toList();

        assertThat(ids).containsExactlyInAnyOrder(recruiting, bidding);
    }

    @Test
    @DisplayName("按金额区间与分类筛选生效")
    void filters_work() {
        String prefix = "T12-FILTER-" + RANDOM.nextInt(1_000_000) + "-";
        Long cheap = seedTask("RECRUITING", "DEV", prefix + "cheap", new BigDecimal("100.00"));
        seedTask("RECRUITING", "DEV", prefix + "pricey", new BigDecimal("9000.00"));

        HallQuery q = new HallQuery();
        q.setKeyword(prefix);
        q.setAmountMin(new BigDecimal("50.00"));
        q.setAmountMax(new BigDecimal("500.00"));

        List<Long> ids = queryService.hall(q).getList().stream().map(TaskSummary::getTaskId).toList();
        assertThat(ids).containsExactly(cheap);
    }

    @Test
    @DisplayName("返回体带 statusName 与 allowedActions，前端不必硬编码状态（G9/多端要求）")
    void summary_exposes_status_metadata() {
        String prefix = "T12-META-" + RANDOM.nextInt(1_000_000) + "-";
        seedTask("RECRUITING", "DEV", prefix + "m", new BigDecimal("500.00"));

        HallQuery q = new HallQuery();
        q.setKeyword(prefix);
        TaskSummary t = queryService.hall(q).getList().get(0);

        assertThat(t.getStatus()).isEqualTo("RECRUITING");
        assertThat(t.getStatusName()).isEqualTo("招募中");
        assertThat(t.getStatusGroup()).isEqualTo("RECRUITING");
    }
}
