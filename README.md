# 任务撮合平台 · 设计工程

> 一个「任务发布 / 承接」的双边撮合平台。**Web 端先行设计**，桌面端 / 微信小程序 / 手机 App 复用同一后端。
> 角色：**用户**（既能发单也能接单）· **操作员**（内容审核 / 争端裁决）· **系统管理员**（账号、权限、费率、风控）。
> 接单两种模式：**抢单**（先到先得）· **竞拍**（接单方提条件，发单方选定，支持单选与多选）。

---

## 一、产物清单

| 目录 | 产物 | 规模 | 状态 |
|---|---|---|---|
| `docs/` | [总体设计文档](docs/2026-10-15-任务撮合平台-总体设计.md) | **v1.6** · 4688 行 · 30 张 Mermaid 图 | ✅ |
| `docs/plans/` | [三端交付路线图](docs/plans/2026-10-15-三端交付路线图.md) | Web → 小程序 → App 串行 20 周 | ✅ |
| `docs/plans/` | [P1 后端与 Web 端实施计划](docs/plans/2026-10-15-P1-后端与Web端实施计划.md) | **41 任务 · 117 个 TDD 步骤** | ✅ |
| `docs/` | [第二轮代码审查与修复](docs/2026-10-15-第二轮代码审查与修复.md) | 9 项 Critical 的处置与遗留 | ✅ |
| `db/migration/` | `V1`–`V8` Flyway 迁移 | **71 表 · 898 列 · 256 索引 · 41 个 CHECK** | ✅ MySQL 8.4 实跑通过（`MigrationSmokeTest`） |
| `tools/` | [check-consistency](tools/check-consistency/) | **五道跨产物一致性门禁** | ✅ 自测 **14/14**（含 4 条 `must-fail` 反向验证）· 真实产物 0 违规 |
| `platform-*/` | [M0 工程骨架](deploy/README.md) | Spring Boot 3.2 + JDK 21 | ✅ **211 个测试全绿** · 可启动 |
| `api/` | [openapi.yaml](api/openapi.yaml) | **110 路径 · 138 操作 · 63 schema** | ⚠️ 机器校验**仅覆盖 DDL↔文档↔权限点↔事件**，⛔ 不含 spec↔实现（见审查报告） |
| `api/` | [websocket-protocol.md](api/websocket-protocol.md) | IM 长连接协议（16 种消息 + 离线补偿） | ✅ |
| `prototype/mp-weixin/` | [index.html](prototype/mp-weixin/index.html) | 小程序 **14 屏**，单文件零外部依赖 | ✅ |
| `prototype/web-admin/` | [index.html](prototype/web-admin/index.html) | 管理端 **12 屏**（9 岗位裁剪菜单） | ✅ |

## 交付节奏（三端为主，桌面端移出近期路线图）

| 阶段 | 端 | 工期 | 出口标准 |
|---|---|---|---|
| **P1** | `web-user` + `web-admin` + 后端 | **11 周** | 全链路跑通 · 5000 并发抢单唯一性 · 资金不变量零差异 · 越权用例 T1–T34 全绿 |
| **P2** | 微信小程序（**uni-app**） | **4 周** | 浏览/抢单/报价/IM/支付/看进度 · 订阅消息与定位合规 · 简化发单 |
| **P3** | 手机 App（**uni-app 同一套代码**） | **5 周** | 推送抢单 · 扫码签到 · 大文件交付 · 生物识别登录 · 双端上架 |

**技术选型补充**（v1.6 之后新定，设计文档尚未收录）：手机 App 与微信小程序**共用 uni-app（Vue 3 语法）一套代码**，`#ifdef` 条件编译**只允许出现在 `src/platform/`**（支付 / 推送 / 上传 / 定位四处必然分叉）。

---

## 一之二、跨产物一致性门禁（⭐ 最便宜也最值钱的一道检查）

```bash
cd tools/check-consistency && pnpm install
pnpm test          # 门禁自测：good* 必须 0 违规、bad* 必须抓到、must-fail* 必须响亮失败
pnpm check         # 对真实产物执行五道门禁
```

| 门禁 | 检查什么 | 抓的是哪类错 |
|---|---|---|
| ① DDL ↔ OpenAPI | 实体 schema 的每个字段必须是那张表的列（或在 schema 里声明 `x-computed` / `x-field-map`） | 接口字段拼错、引用了已改名的列 |
| ② **DDL ↔ 文档** | 文档里的 `表.列` 引用必须存在 | ⭐ **§6.7 P0-① 那一类：文档与 DDL 直接矛盾** |
| ③ OpenAPI ↔ DDL | 每张表都要有接口读写，或在白名单里声明为内部表 | 建了表忘了做接口（功能建一半） |
| ④ 权限点 ↔ 代码 | `@PreAuthorize` 的权限点必须在 `sys_permission` 里 | 权限点写错一个字母 → 接口对所有人 403 |
| ⑤ 事件 ↔ 消费方 | 事件的 producer/consumers 必须是真实模块 | 事件发出去没人消费 → 业务静默断链 |

**门禁的设计取舍**（详见 [tools/check-consistency/README.md](tools/check-consistency/README.md)）：

- **宁可精确，不要贪全**。第一版对全部 63 个 schema 一视同仁，结果几十个计算字段被误报 —— 白名单会变成垃圾桶，而**误报多的门禁一定会被关掉**。改成「只校验能明确映射到真实表的 18 个 schema」，反而更严（要求字段属于**正确的那张表**）。
- **「bad 夹具必须报错」比「good 夹具通过」重要**。我们真正要防的是**门禁悄悄失效**。
- **验收标准不是"门禁通过"，而是"故意注入缺陷它必须报错"**。
- ⛔ **解析失败必须是硬错误**（`must-fail-*` 夹具就是为它立的）：实测过「括号不配平 ⇒ 整张表从五道门禁里消失 ⇒ 同一个字段注入从『报错』变成『0 违规』」。第二轮的独立审查复现了这条，已修（`lib/ddl.mjs` 现在直接 throw）。
- ⚠️ **覆盖度要如实看**：门禁③ 的 71 张表里，只有 12 张靠 schema 名精确命中，**39 张是靠通用词（task/order/log/detail…）通过**的；门禁④ 目前只覆盖 **1 处** `@PreAuthorize`（全仓就这一处）。"0 违规"要连覆盖面一起读。

## 二、快速验证

### 1. 数据库迁移

```bash
mysql -uroot -p --default-character-set=utf8mb4 -e \
  "CREATE DATABASE task_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

cd db/migration
for f in V1__schema_identity_task.sql V2__schema_award_order_credit.sql \
         V3__schema_wallet_dispute_infra.sql V4__seed_data.sql \
         V5__seed_category.sql V6__seed_ops_security.sql \
         V7__fix_audit_table_comments.sql V8__seed_sms_guardrails.sql; do
  mysql -uroot -p --default-character-set=utf8mb4 task_platform -e "source $f"
done
```

> ⚠️ **两个必踩的坑**：
> 1. MySQL 客户端的 `source` **打不开中文路径**的文件 —— 先 `cd` 到 ASCII 路径或复制到临时目录。
> 2. **必须带 `--default-character-set=utf8mb4`**，否则中文 COMMENT 与字符串被按 latin1 解析，报出**位置完全对不上**的语法错误。
>
> ⚠️ **改了已应用过的迁移文件后必须重建库**：Flyway 会校验 checksum，
> 否则所有 `@SpringBootTest` 会因上下文加载失败集体报错（踩过：改 V8 的取值后 51 个测试同时红）。
> 测试库重建：`DROP DATABASE task_platform_test; CREATE DATABASE task_platform_test ...;`

`V4` / `V5` / `V6` 末尾内置**自检查询**，有输出即代表数据不自洽：
信誉等级区间连续性 · 费率 BASE 兜底 · 分类子节点完整性 · 互斥岗位引用 · **平台科目账户齐三类** · **应急开关 ≥5 个** · **核心链路限流规则存在** · **im_message 已登记归档**。

### 2. 接口契约

```bash
# 导入 Swagger Editor / Apifox / Postman
api/openapi.yaml

# 生成前端 TS 类型（⛔ 禁止手写接口 interface）
npx openapi-typescript api/openapi.yaml -o packages/shared/src/api-types.ts
```

### 3. 原型

直接用浏览器打开（**无需服务器、无需联网**）：

- `prototype/mp-weixin/index.html` —— 14 屏，顶部锚点索引 + 65 个热区 + 「仅看单屏」翻屏
- `prototype/web-admin/index.html` —— 12 屏，**左侧菜单按 9 个岗位动态裁剪**

---

## 三、设计里最需要先看的八件事

| # | 结论 | 在哪 |
|---|---|---|
| 1 | ⛔ **资金不得归集**：平台用自有账户收款再转付 = **二清**，是监管红线。佣金**只能通过渠道分账指令划取** | §5.5.4 |
| 2 | ⭐ **抢单唯一性只能由数据库判定**：条件 `UPDATE ... WHERE status='RECRUITING' AND assignee_id IS NULL` + 判断影响行数。Redis 锁仅削峰 | §5.5.1 |
| 3 | ⭐ **只算佣金，实得用减法**：`实得 = 基数 − 佣金`。两边各算一次会因舍入方向不同而不配平 | §5.12.3 |
| 4 | ⭐ **必须建「验收标准」**（`task_acceptance_item`）：没有它，发单方恶意驳回时仲裁专员**没有任何客观依据** | §5.14.1 G1 |
| 5 | ⭐ **`delivery_unit` 统一结算单元**：让 Phase 2 放开「里程碑 × 多人拆单」时**不必重写结算链路** | §5.14.3 |
| 6 | ⭐ **`escrow_share` 托管份额**：没有它，「只冻结 A 的份额、B/C 正常结算」无法落地 | §5.5.3 · §6.7 P0-① |
| 7 | ⭐ **审计哈希链必须按小时分段**：并发下按"整条链"建链必断 | §5.8.4 · §6.7 P0-③ |
| 8 | ⭐ **多端预留的 10 件事**（尤其"托管创建接口预留 `X-Client` 分支"）：现在做成本几乎为零，将来补做要重写支付链路 | §5.15.12 |

---

## 四、分阶段路线

| 阶段 | 内容 | 工期 |
|---|---|---|
| **Phase 1（MVP）** | 身份 + 任务（三维类型 / 18 状态）+ 抢单 + 竞拍单选 + 托管分账 + **抽成引擎** + **信誉分引擎** + 基础 IM + 管理端 + 双人复核 | **10–11 周** |
| Phase 2 | MULTI 多选中标拆单 · 争端工单 · 评价与申诉 · 活动费率 · 风控规则 · 报表对账 · 分批结算 | 6–8 周 |
| Phase 3 | 微信小程序 · 手机 App · 桌面端 · ES 检索（含 geo_distance 解决距离排序） | 8–10 周 |
| Phase 4 | 按阈值拆模块 · 多机房容灾 · 开放平台 | 按需 |

**拆分阈值**：单模块 QPS > 2000 · 表 > 5000 万行 · 发布频率是其他模块 3 倍 · IM 连接数 > 5 万 · 团队 > 15 人。
⛔ **`wallet` 与 `dispute` 最后拆或不拆** —— 资金与仲裁最需要强一致与审计完整性。

---

## 五、产出过程的两次"实跑抓错"（值得记下的方法）

### 第一次：DDL 实跑抓出 3 个真问题

| # | 问题 | 性质 |
|---|---|---|
| 1 | DDL 少打一个反引号（`` `rule_type <> `` 而非 `` `rule_type` <> ``）→ MySQL 把整段当成一个标识符。**逐行阅读完全看不出，靠实跑报错 + 十六进制才定位** | 写作错误 |
| 2 | `credit_rule.description` 显式插入 NULL 到 `NOT NULL DEFAULT ''` 列（DEFAULT 只在列省略时生效） | 建模疏漏 |
| 3 | ⭐ **「平台认证」与「优秀」分数档重叠**（都占 80–100）→ 85 分用户无法判定等级。**根因是「平台认证」不是分数档而是人工授予标记** → 加 `award_mode`(AUTO/MANUAL) | **设计缺陷** |

### 第二次：跨产物审计抓出 14 项缺陷（§6.7）

**三项 P0 都是"文档与 DDL 直接矛盾"** —— 单看文档自洽、单看 DDL 也自洽，**矛盾只在两者之间**：

- **P0-①** 文档 §5.5.3 写「记账单位是子订单份额（`escrow.sub_amounts`）」，但 `escrow` 表只有总额字段 → 补 `escrow_share`
- **P0-②** Q18 定公益佣金为 0，但 `ck_comm_final_rate` 是 `BETWEEN 0.01 AND 0.20` → 放宽下限
- **P0-③** 审计哈希链要求「含前一条哈希」，但并发下无法确定"前一条" → 改按小时分段建链

### 由此沉淀的四条工程纪律

| 纪律 | 说明 |
|---|---|
| **① 契约必须能跑** | 涉及数据库与接口的设计，交付物必须是**可执行文件**（能 `source`、能解析、能被 codegen 消费），而不是文档里的代码块 |
| **② 自检要写进产物里** | 种子数据文件末尾必须带自检查询，让"数据不自洽"在**执行时**报错 |
| **③ 跨产物对照要自动化** | 建议固化为 5 条 CI 门禁：DDL↔OpenAPI · DDL↔文档 · OpenAPI↔DDL · 权限点↔代码 · 事件↔消费方（§6.7.5） |
| **④ 写脚本时警惕引号层级** | 中文文案里的 `"` `'` 会破坏 JS/Python 字符串；PowerShell 的反引号是转义符；SQL 模板串里的注释别用反引号 |

---

## 六、下一步

| 步骤 | 状态 |
|---|---|
| §6.1 待确认清单 32 项 | ✅ 全部闭环 |
| 数据库 DDL / OpenAPI / 原型 | ✅ 已完成并机器验证 |
| **实施计划（writing-plans）** | ⬜ **下一步** —— 把 Phase 1 的 11 周拆成可执行任务（粒度 / 依赖顺序 / 验收标准 / 负责人） |
| 详细设计（剩余） | ⬜ 状态机迁移表落地代码 · `web-user` / `web-admin` 页面清单与路由表 · 支付渠道对接细则 |
| 高保真原型 | ⬜ Figma |
| 环境 + 骨架 | ⬜ Docker Compose · CI 流水线（含上述 5 条一致性门禁 + gitleaks/SAST/SCA） |

### 开工前仅剩两项外部确认（不阻塞开发）

- **Q13 个税代扣的执行细节** —— 需税务书面确认申报口径（决定已定为"平台代扣代缴·劳务报酬所得"）
- **Q25 知识产权条款文本** —— 需法务定稿（决定已定为"默认归发单方、设计开发类必填显式确认"）
