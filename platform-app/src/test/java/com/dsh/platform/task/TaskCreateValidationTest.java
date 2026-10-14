package com.dsh.platform.task;

import com.dsh.platform.common.BizException;
import com.dsh.platform.common.ErrorCode;
import com.dsh.platform.identity.dto.LoginResult;
import com.dsh.platform.identity.dto.SmsScene;
import com.dsh.platform.identity.service.AuthService;
import com.dsh.platform.identity.service.SmsCodeService;
import com.dsh.platform.task.dto.TaskCreateRequest;
import com.dsh.platform.task.dto.TaskCreateResult;
import com.dsh.platform.task.service.TaskCommandService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T9 · 任务创建与<b>三维动态校验</b>。
 *
 * <p>"三维"指 业务分类 × 任务形态(task_form) × 付款方式(pay_mode) 三者组合，
 * 组合不同则必填项与合法取值都不同。§5.14.5 把校验分成五类：
 * <ol>
 *   <li><b>必填</b> —— 任何任务都要有：标题、分类、预算、截止时间、验收标准；</li>
 *   <li><b>条件必填</b> —— 选了 OFFLINE 才要地点与服务时段；选了 BY_QUANTITY 才要单价与数量；</li>
 *   <li><b>互斥</b> —— 付款方式与接单方式必须配套（一口价只能抢单）；</li>
 *   <li><b>分类约束</b> —— 取值必须落在该分类的 allow_forms / allow_pay_modes 内（T8 提供）；</li>
 *   <li><b>风险</b> —— 里程碑预算必须配平、MILESTONE × MULTI 互斥等。</li>
 * </ol>
 *
 * <p>⛔ 前两类是"填错要报错"，后三类是"设计上就不允许"。混在一起写会漏掉后三类 ——
 * 而它们正是资损与争议的来源（比如里程碑金额之和与预算对不上，结算时必然出问题）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskCreateValidationTest {

    private static final Random RANDOM = new Random();

    @Autowired
    private TaskCommandService taskCommandService;

    @Autowired
    private AuthService authService;

    @Autowired
    private SmsCodeService smsCodeService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private Long publisherId;

    @BeforeAll
    void cleanAndCreatePublisher() {
        var keys = redis.keys("sms:limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        String phone = "138" + String.format("%08d", RANDOM.nextInt(100_000_000));
        String ip = "10." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(254);
        String code = smsCodeService.send(phone, SmsScene.REGISTER, ip, "dev-" + RANDOM.nextLong());
        LoginResult u = authService.register(phone, "Passw0rd!", "发单方", code);
        publisherId = u.getUserId();
    }

    // ------------------------------------------------------------------
    // 构造请求的辅助方法（⛔ 不要在每个用例里手写完整请求体：必然漂移）
    // ------------------------------------------------------------------

    /** 基准请求：软件开发 / 线上 / 竞拍 —— 一个合法的最小任务。 */
    private TaskCreateRequest base() {
        TaskCreateRequest r = new TaskCreateRequest();
        r.setCategoryCode("DEV");
        r.setTaskForm("ONLINE");
        r.setPayMode("BID");
        r.setGrantMode("BID");
        r.setAwardMode("SINGLE");
        r.setTitle("开发一个企业内部管理系统");
        r.setDescription("需求详见附件，需提供源码与部署文档。");
        r.setBudgetMin(new BigDecimal("3000.00"));
        r.setBudgetMax(new BigDecimal("5000.00"));
        r.setDeadline(java.time.LocalDateTime.now().plusDays(14));
        r.setAcceptDays(7);
        r.setAcceptanceItems(List.of(item("完成登录与权限模块"), item("提供部署文档")));
        return r;
    }

    private TaskCreateRequest.AcceptanceItem item(String name) {
        TaskCreateRequest.AcceptanceItem i = new TaskCreateRequest.AcceptanceItem();
        i.setItemName(name);
        return i;
    }

    private void assertParamInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("基线请求可创建成功，并落库（任务 + 验收标准）")
    void valid_request_creates_task() {
        TaskCreateResult r = taskCommandService.create(base(), publisherId);

        assertThat(r.getTaskId()).isNotNull();
        assertThat(r.getTaskNo()).startsWith("T");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_acceptance_item WHERE task_id = ?", Integer.class, r.getTaskId()))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("⭐ 条件必填：OFFLINE 任务必须有地点，且地点必须带经纬度与服务时段")
    void offline_task_requires_location_with_geo_and_slots() {
        // ① 完全没给地点
        TaskCreateRequest noLoc = base();
        noLoc.setTaskForm("OFFLINE");
        noLoc.setCategoryCode("LIFE");         // 生活服务允许 OFFLINE
        noLoc.setPayMode("FIXED");
        noLoc.setGrantMode("GRAB");
        noLoc.setPrice(new BigDecimal("200.00"));
        assertParamInvalid(() -> taskCommandService.create(noLoc, publisherId));

        // ② 给了地点但没经纬度 —— 线下任务没有坐标就无法算距离、无法按距离排序
        TaskCreateRequest noGeo = base();
        noGeo.setTaskForm("OFFLINE");
        noGeo.setCategoryCode("LIFE");
        noGeo.setPayMode("FIXED");
        noGeo.setGrantMode("GRAB");
        noGeo.setPrice(new BigDecimal("200.00"));
        TaskCreateRequest.Location loc = new TaskCreateRequest.Location();
        loc.setProvince("河南省");
        loc.setCity("郑州市");
        loc.setAddress("金水区某小区");
        noGeo.setLocation(loc);
        assertParamInvalid(() -> taskCommandService.create(noGeo, publisherId));

        // ③ 坐标齐全但缺服务时段 —— 线下履约必须有约定时间窗
        TaskCreateRequest noSlot = base();
        noSlot.setTaskForm("OFFLINE");
        noSlot.setCategoryCode("LIFE");
        noSlot.setPayMode("FIXED");
        noSlot.setGrantMode("GRAB");
        noSlot.setPrice(new BigDecimal("200.00"));
        TaskCreateRequest.Location loc2 = new TaskCreateRequest.Location();
        loc2.setProvince("河南省");
        loc2.setCity("郑州市");
        loc2.setAddress("金水区某小区");
        loc2.setLng(new BigDecimal("113.660000"));
        loc2.setLat(new BigDecimal("34.800000"));
        noSlot.setLocation(loc2);
        assertParamInvalid(() -> taskCommandService.create(noSlot, publisherId));
    }

    @Test
    @DisplayName("⭐ 互斥：一口价(FIXED) 只能抢单(GRAB)，不能配竞拍")
    void fixed_pay_mode_must_pair_with_grab() {
        TaskCreateRequest r = base();
        r.setPayMode("FIXED");
        r.setPrice(new BigDecimal("3000.00"));
        r.setGrantMode("BID");        // ⛔ 一口价 + 竞拍 = 价格已定还要大家报价，语义矛盾

        assertParamInvalid(() -> taskCommandService.create(r, publisherId));
    }

    @Test
    @DisplayName("⭐ 风险：里程碑至少 2 期，且金额之和必须等于预算（不配平=结算必出问题）")
    void milestone_requires_at_least_two_stages_summing_to_budget() {
        // ① 只有 1 期
        TaskCreateRequest one = milestoneBase();
        one.setMilestones(List.of(ms(1, "设计", "3000.00")));
        assertParamInvalid(() -> taskCommandService.create(one, publisherId));

        // ② 两期但之和不等于预算
        TaskCreateRequest mismatch = milestoneBase();
        mismatch.setMilestones(List.of(ms(1, "设计", "3000.00"), ms(2, "开发", "5000.00")));
        assertParamInvalid(() -> taskCommandService.create(mismatch, publisherId));

        // ③ 两期且配平 —— 必须通过（否则校验过严会把合法用例也挡掉）
        TaskCreateRequest ok = milestoneBase();
        ok.setMilestones(List.of(ms(1, "设计", "3000.00"), ms(2, "开发", "5000.00")));
        ok.setBudgetMin(new BigDecimal("8000.00"));
        ok.setBudgetMax(new BigDecimal("8000.00"));
        assertThatCode(() -> taskCommandService.create(ok, publisherId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⭐ Phase 1 限制：MILESTONE 只能单选(SINGLE)，不支持里程碑 × 多中标")
    void milestone_must_be_single_award_mode_in_phase1() {
        TaskCreateRequest r = milestoneBase();
        r.setMilestones(List.of(ms(1, "设计", "4000.00"), ms(2, "开发", "4000.00")));
        r.setBudgetMin(new BigDecimal("8000.00"));
        r.setBudgetMax(new BigDecimal("8000.00"));
        r.setAwardMode("MULTI");
        r.setRequiredCount(3);

        assertParamInvalid(() -> taskCommandService.create(r, publisherId));
    }

    @Test
    @DisplayName("⭐ 验收标准是强制的：至少 1 项（没有它，恶意驳回时仲裁没有任何客观依据）")
    void acceptance_items_are_mandatory_and_at_least_one() {
        TaskCreateRequest empty = base();
        empty.setAcceptanceItems(List.of());
        assertParamInvalid(() -> taskCommandService.create(empty, publisherId));

        TaskCreateRequest nullItems = base();
        nullItems.setAcceptanceItems(null);
        assertParamInvalid(() -> taskCommandService.create(nullItems, publisherId));

        // 空名字的验收项也要挡住
        TaskCreateRequest blank = base();
        TaskCreateRequest.AcceptanceItem bad = new TaskCreateRequest.AcceptanceItem();
        bad.setItemName("   ");
        blank.setAcceptanceItems(new ArrayList<>(List.of(bad)));
        assertParamInvalid(() -> taskCommandService.create(blank, publisherId));
    }

    @Test
    @DisplayName("⭐ 分类约束：跑腿代办不能选竞拍，也不能选线上")
    void category_constraints_are_enforced() {
        // ERRAND 的 allow_pay_modes = [FIXED]，给 BID 必须被拒
        TaskCreateRequest bid = base();
        bid.setCategoryCode("ERRAND");
        bid.setTaskForm("OFFLINE");
        bid.setPayMode("BID");
        bid.setGrantMode("BID");
        assertParamInvalid(() -> taskCommandService.create(bid, publisherId));

        // ERRAND 的 allow_forms = [OFFLINE]，给 ONLINE 必须被拒
        TaskCreateRequest online = base();
        online.setCategoryCode("ERRAND");
        online.setTaskForm("ONLINE");
        online.setPayMode("FIXED");
        online.setGrantMode("GRAB");
        online.setPrice(new BigDecimal("50.00"));
        assertParamInvalid(() -> taskCommandService.create(online, publisherId));
    }

    // ------------------------------------------------------------------

    private TaskCreateRequest milestoneBase() {
        TaskCreateRequest r = base();
        r.setPayMode("MILESTONE");
        r.setGrantMode("BID");
        r.setAwardMode("SINGLE");
        return r;
    }

    private TaskCreateRequest.Milestone ms(int seq, String name, String amount) {
        TaskCreateRequest.Milestone m = new TaskCreateRequest.Milestone();
        m.setSeq(seq);
        m.setName(name);
        m.setAmount(new BigDecimal(amount));
        return m;
    }
}
