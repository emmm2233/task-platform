package com.dsh.platform.fund;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.fund.service.DeliveryService;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.SmsCodeService;
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
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 履约侧：提交交付物与返工。
 *
 * <p>⭐ 与 {@link AcceptanceServiceTest} 合起来才是完整的履约闭环：
 * <pre>
 *   接单方提交交付物  IN_PROGRESS → PENDING_ACCEPT   （本类）
 *   发单方驳回        PENDING_ACCEPT → REVISION      （本类，轮次 +1）
 *   接单方重新交付    REVISION → PENDING_ACCEPT      （本类）
 *   发单方验收        PENDING_ACCEPT → COMPLETED     （AcceptanceService，已通）
 * </pre>
 *
 * <p>⭐ 两条必须守住的：
 * <ol>
 *   <li><b>只有接单方能提交交付物</b>（发单方替接单方"交付"是荒谬的，
 *       但接口鉴权写漏时它就会发生）；</li>
 *   <li>⭐ <b>修改轮次必须有上限并自动收口</b>：没有上限的话，
 *       发单方可以无限驳回、接单方永远拿不到钱 —— 这是最典型的权力失衡。
 *       轮次用尽必须自动转争议（由平台裁决），而不是继续让双方拉锯。</li>
 * </ol>
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryServiceTest {

    private static final Random RANDOM = new Random();

    @Autowired private DeliveryService deliveryService;
    @Autowired private AuthService authService;
    @Autowired private SmsCodeService smsCodeService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private Long publisherId;
    private Long takerId;

    @BeforeAll
    void setUp() {
        var keys = redis.keys("sms:limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        publisherId = newUser("发单方");
        takerId = newUser("接单方");
    }

    private Long newUser(String nickname) {
        String phone = "138" + String.format("%08d", RANDOM.nextInt(100_000_000));
        String ip = "10." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(254);
        String code = smsCodeService.send(phone, SmsScene.REGISTER, ip, "dev-" + RANDOM.nextLong());
        return authService.register(phone, "Passw0rd!", nickname, code).getUserId();
    }

    /** 造一个"履约中 + 已指派"的任务。modifyRoundLimit 可指定（默认 3）。 */
    private Long seedInProgress(Integer roundLimit) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM task_category WHERE code = 'DEV'", Long.class);
        jdbc.update("""
                INSERT INTO task (task_no, publisher_id, category_id, category_path, task_form, pay_mode,
                                  title, description, grant_mode, award_mode, required_count,
                                  price, budget_min, budget_max, deadline, status, audit_status,
                                  assignee_id, modify_round_limit, escrow_amount, source_client, version)
                VALUES (?,?,?,'/','ONLINE','BID', ?,?, 'BID','SINGLE',1, ?, 4000.00, 5000.00, ?,
                        'IN_PROGRESS','PASSED', ?, ?, 5000.00, 'web-user', 0)
                """,
                "D" + Long.toString(System.nanoTime(), 36), publisherId, categoryId,
                "履约测试", "验证交付与返工", null, LocalDateTime.now().plusDays(20),
                takerId, roundLimit);
        Long taskId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        // ⚠️ 补一张 biz_order：子订单要挂在它上面（真实链路里由托管产生）。
        //    本测试测的是交付与返工，所以直接建单据，不走完整的托管流程。
        jdbc.update("""
                INSERT INTO biz_order (order_no, task_id, publisher_id, total_amount,
                                       sub_order_count, settled_count, created_at, updated_at)
                VALUES (?,?,?,?, 0, 0, NOW(3), NOW(3))
                """, "O" + Long.toString(System.nanoTime(), 36), taskId, publisherId,
                new BigDecimal("5000.00"));
        return taskId;
    }

    private String statusOf(Long taskId) {
        return jdbc.queryForObject("SELECT status FROM task WHERE id = ?", String.class, taskId);
    }

    private Integer roundOf(Long taskId) {
        var v = jdbc.queryForList("SELECT modify_round FROM sub_order WHERE task_id = ?",
                Integer.class, taskId);
        return v.isEmpty() ? null : v.get(0);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ 接单方提交交付物：IN_PROGRESS → PENDING_ACCEPT")
    void taker_submits_delivery() {
        Long taskId = seedInProgress(3);

        deliveryService.submit(taskId, takerId, "已完成全部功能，含部署文档");

        assertThat(statusOf(taskId)).isEqualTo(TaskStatus.PENDING_ACCEPT.name());
    }

    @Test
    @DisplayName("⭐ 只有接单方能提交交付物（发单方替人交付必须被拒）")
    void only_taker_can_submit() {
        Long taskId = seedInProgress(3);

        assertThatThrownBy(() -> deliveryService.submit(taskId, publisherId, "我替他交了"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_TASK_OWNER.getCode());
        assertThat(statusOf(taskId)).isEqualTo(TaskStatus.IN_PROGRESS.name());
    }

    @Test
    @DisplayName("⭐ 驳回：PENDING_ACCEPT → REVISION，修改轮次 +1，理由必填")
    void reject_moves_to_revision_and_counts_round() {
        Long taskId = seedInProgress(3);
        deliveryService.submit(taskId, takerId, "第一版");

        // 不写理由 → 拒
        assertThatThrownBy(() -> deliveryService.reject(taskId, publisherId, "  "))
                .as("没有理由的驳回，接单方不知道改什么")
                .isInstanceOf(BizException.class);
        assertThat(statusOf(taskId)).isEqualTo(TaskStatus.PENDING_ACCEPT.name());

        deliveryService.reject(taskId, publisherId, "登录页缺少错误提示");

        assertThat(statusOf(taskId)).isEqualTo(TaskStatus.REVISION.name());
        assertThat(roundOf(taskId)).as("修改轮次要 +1，它是后续判断和计费的依据").isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 返工后可重新交付：REVISION → PENDING_ACCEPT（不是回到 IN_PROGRESS）")
    void revision_can_resubmit() {
        Long taskId = seedInProgress(3);
        deliveryService.submit(taskId, takerId, "第一版");
        deliveryService.reject(taskId, publisherId, "缺文档");

        deliveryService.submit(taskId, takerId, "补了文档");

        assertThat(statusOf(taskId))
                .as("返工后重新提交应回到待验收，而不是退回履约中")
                .isEqualTo(TaskStatus.PENDING_ACCEPT.name());
    }

    @Test
    @DisplayName("⭐⭐ 轮次用尽自动转争议：不能让发单方无限驳回")
    void rounds_exhausted_goes_to_dispute() {
        Long taskId = seedInProgress(2);   // 上限 2 轮

        // 第 1 轮
        deliveryService.submit(taskId, takerId, "v1");
        deliveryService.reject(taskId, publisherId, "改这里");
        assertThat(statusOf(taskId)).isEqualTo(TaskStatus.REVISION.name());

        // 第 2 轮
        deliveryService.submit(taskId, takerId, "v2");
        deliveryService.reject(taskId, publisherId, "再改");
        assertThat(roundOf(taskId)).isEqualTo(2);

        // 第 3 次驳回时轮次已用尽 —— ⛔ 不能让发单方继续驳回
        assertThatThrownBy(() -> deliveryService.reject(taskId, publisherId, "还要改"))
                .as("轮次用尽后不能再驳回，否则接单方永远拿不到钱")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TASK_STATUS_INVALID.getCode());
    }

    @Test
    @DisplayName("状态不对不能提交：待验收时重复提交必须被拒")
    void cannot_submit_twice() {
        Long taskId = seedInProgress(3);
        deliveryService.submit(taskId, takerId, "v1");

        assertThatThrownBy(() -> deliveryService.submit(taskId, takerId, "v2"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TASK_STATUS_INVALID.getCode());
    }
}
