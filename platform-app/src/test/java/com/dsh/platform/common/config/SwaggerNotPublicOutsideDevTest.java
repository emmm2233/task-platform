package com.dsh.platform.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ⭐ Swagger / OpenAPI 在【非纯开发】profile 下必须 401。
 *
 * <h2>为什么补这个类</h2>
 * 阶段 0.1 的 commit 自述「改了但<b>没有验证成功</b>」——作者只能手工 curl，
 * 得出「allMatch 没产生效果」的<b>错误结论</b>（真实原因是那次 dev,prod 实例被
 * {@link StartupSecretGuard} 拒绝启动，回答 200 的是残留在端口上的 dev 实例）。
 * <b>根因不是代码，而是缺一条能自动跑的断言</b>：没有它，任何一次安全检查都只能靠人肉
 * curl + 人眼判断"是哪个进程在回答"。本类把它变成可回归的。
 *
 * <h2>为什么不只测一种 profile 组合</h2>
 * {@link ProfileGuard} 的判定修过一次真实的 fail-open：读 {@code spring.profiles.active}
 * <b>属性</b>而不是真实激活的 profile，于是 {@code active: dev} + {@code include: prod}
 * 会让 Swagger 按 dev 放行（属性只显示 dev，真实 profile 是 [prod, dev]）。
 * 因此这里用 {@code @ActiveProfiles({"dev","prod"})} 跑"混用"形态，
 * 而 include/group 那条路径由 {@link ProfileGuardTest} 直接钉住判定函数。
 */
@SpringBootTest(properties = {
        // ⛔ 这三个必须注入：dev,prod 混用会被 StartupSecretGuard 按生产标准校验，
        //    用开发默认值会直接拒绝启动（这正是它该做的事）。
        "platform.crypto.jwt-secret=test-only-jwt-secret-for-gating-0123456789abcdef",
        "platform.crypto.kek=" + SwaggerNotPublicOutsideDevTest.TEST_KEK,
        "platform.crypto.phone-salt=test-only-phone-salt-for-gating",
})
@ActiveProfiles({"dev", "prod"})
@AutoConfigureMockMvc
class SwaggerNotPublicOutsideDevTest {

    /** 32 字节（AES-256）的合法 KEK，仅测试用 —— ⛔ 与 application-dev.yml 的默认值不同。 */
    static final String TEST_KEK = "dGVzdC1vbmx5LWtlay0zMmJ5dGVzLWZvci10ZXN0cyE=";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("⭐⭐ 混用非开发 profile 时，接口文档必须 401（不能匿名拿到完整接口表）")
    void apiDocs_is_not_public_when_a_non_dev_profile_is_active() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("放行名单该给的仍然给：健康检查与大厅读接口不受影响")
    void public_endpoints_still_work() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        // 大厅匿名浏览（按 HttpMethod.GET 放行）—— 这条防的是"为了关 Swagger 把放行名单一起改坏"
        mvc.perform(get("/api/v1/categories")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("未放行的业务接口仍然 401（默认拒绝没被破坏）")
    void non_public_endpoint_is_still_401() throws Exception {
        mvc.perform(get("/api/v1/account/profile")).andExpect(status().isUnauthorized());
    }
}
