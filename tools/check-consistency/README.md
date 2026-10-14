# 跨产物一致性门禁

> 五道门禁，检查 **DDL ↔ OpenAPI ↔ 文档 ↔ 权限点 ↔ 事件** 之间是否自洽。

## 为什么需要它

§6.7 的 **P0-①** 是「文档 §5.5.3 写『记账单位是子订单份额（`escrow.sub_amounts`）』，但 `escrow` 表根本没有这一列」。

这类缺陷有个共同形状：**单看文档自洽、单看 DDL 也自洽，矛盾只在两者之间**。
逐份人工评审抓不住它 —— 必须把两份摆在一起逐字段比对。

门禁写完当天就抓出了 §6.7.6 的两个新缺陷（OpenAPI 重复 path 键、`escrow_share` 建了表却没接口）。

## 用法

```bash
cd tools/check-consistency
pnpm install        # 只有 yaml 一个依赖

pnpm test           # 门禁自测：good* 必须 0 违规、bad* 必须抓到、must-fail* 必须响亮失败
pnpm check          # 对真实产物执行五道门禁
node check.mjs --warn   # 违规只警告不失败（本地调试用，⛔ 不要用到 CI）
```

## 五道门禁

| # | 名称 | 检查什么 | 抓的是哪类错 |
|---|---|---|---|
| ① | `ddl-vs-openapi` | 实体 schema 的每个字段必须是**那张表**的列 | 接口字段拼错、引用了已改名的列 |
| ② | `ddl-vs-doc` | 文档里的 `` `表.列` `` 引用必须存在 | ⭐ **P0-① 那一类：文档与 DDL 直接矛盾** |
| ③ | `openapi-vs-ddl` | 每张表都要有接口读写，或声明为内部表 | 建了表忘了做接口（功能建一半） |
| ④ | `perm-vs-code` | `@PreAuthorize` 的权限点必须已登记 | 权限点写错一个字母 → 接口对所有人 403 |
| ⑤ | `event-vs-consumer` | 事件的 producer/consumers 必须是真实模块 | 事件发出去没人消费 → 业务静默断链 |

## 三条设计取舍（都是踩过坑才定下来的）

### 1. 宁可精确，不要贪全

第一版对全部 63 个 schema 一视同仁，要求每个属性都在库里存在 ——
结果 `phoneMasked`、`categoryName`、`peerUserId` 等几十个计算/联表字段全部误报。

把它们逐个塞进白名单是**错的做法**：白名单会变成垃圾桶，而**误报多的门禁一定会被关掉**。

改成「只校验能明确映射到真实表的 18 个 schema」后，反而**更严**了 ——
不只要求字段存在，还要求它属于**正确的那张表**（早期版本会把 `credit_log.id` 与 `task.id` 混为一谈）。

对确实与表对应、但含计算字段的 schema，用**就近声明**而不是全局白名单：

```yaml
EscrowInfo:
  x-table: escrow                      # 名字对不上时显式指定（可选）
  x-field-map: { escrowId: id }        # 主键别名：表叫 id，接口叫 escrowId
  x-computed: [shares]                 # 联表/计算字段
```

### 2. 「bad 夹具必须报错」比「good 夹具通过」重要

我们真正要防的是**门禁悄悄失效**，不是它误报。
所以 `test/run.mjs` 按目录名前缀决定「期望什么」，共三类：

| 夹具目录 | 期望 | 防的是什么 |
|---|---|---|
| `good*` | **0 违规** | 误报（误报多的门禁一定会被关掉） |
| `bad*` | **≥1 违规** | 门禁是摆设 |
| `must-fail*` | **抛异常 或 ≥1 违规**（响亮失败即可） | ⭐ **解析层悄悄失败**：产物没解析出来 ⇒ 没东西可校验 ⇒ 零违规 ⇒ 绿灯 |

### 3. ⭐ 验收标准不是「门禁通过」，而是「故意注入缺陷它必须报错」

这条是被打脸打出来的。写门禁的过程中，它自己有两个盲区：

| 盲区 | 怎么暴露的 | 修法 |
|---|---|---|
| 只扫 `docs/`，漏掉 `README.md` | 往 README 注入 `escrow.frozen_share_amount`，门禁**放过了** | `extraDocFiles` 纳入根目录文档 |
| `EscrowInfo` 被当成视图模型跳过 | 把 `escrowNo` 改成 `escrowNoTypo`，门禁**没报错** | 加「剥实体视图后缀再匹配」；⛔ 只剥 `Info`/`Summary`/`Detail`，**不剥** `Request`/`Result`（`AwardResult` 是操作 DTO，曾被错映射到 `award` 表） |

修完后实测 **4/4 命中**：文档引用改坏 · README 注入 · OpenAPI 字段拼错 · 事件消费方填错模块。

### 4. ⛔⛔ 解析失败必须是硬错误，不能 `continue`

第二轮独立审查**实测复现**了一类更隐蔽的失效（`must-fail-*` 夹具就是为它立的）：

```js
// lib/ddl.mjs —— 修复前的写法
const bodyEnd = matchParen(cleaned, bodyStart - 1);
if (bodyEnd < 0) { continue; }        // ⛔ 静默跳过整张表
```

| 注入 | 修复前 | 修复后 |
|---|---|---|
| 往 schema 加一个不存在的属性 | 门禁① 报错 ✅ | 报错 ✅ |
| **同一个注入** + 让那张表的括号不配平 | **0 违规 · exit 0** ❌ | **exit 1**（`checker-crashed` + 指名文件与表）✅ |

为什么危险：表从解析结果里消失后，门禁①不再校验它的字段、门禁③不再要求它有接口 ——
**「解析失败」会把「真缺陷」一起藏起来**。所以：

- `parseDdl` 遇到括号不配平 **直接 throw**（由 `check.mjs` 转成 `checker-crashed` 违规）；
- 一张表都没解析出来也 throw（防门禁③ 空转）；
- 门禁② 补 `nothing-checked`：`refs` 为空即报「这道门禁没在工作」，与门禁①/⑤ 同规格。

同批修掉的两个**误报源**（误报同样会让门禁被关掉）：

- `--` 注释剥离改为**只在引号外**生效（`COMMENT 'rate--flag'` 这类字面量以前会被切掉一半引号）；
- 支持 `ALTER TABLE ... ADD COLUMN`（前向迁移的标准写法，不认就会对**合法的新列**误报；
  V7 已确立「只 ALTER、不改历史迁移」的先例）。

## 目录结构

```
tools/check-consistency/
├── check.mjs                 入口：跑五道门禁，任一失败即 exit 1
├── merge-paths.mjs           工具：合并 openapi.yaml 里被拆开的重复 path 键
├── allowlist.json            ⭐ 白名单，每条都必须写明理由
├── lib/
│   ├── ddl.mjs               解析 CREATE TABLE → 表/列/索引/约束
│   ├── openapi.mjs           解析 OpenAPI → schema 属性（含 $ref 展开、防环）
│   ├── docs.mjs              ⭐ 从文档抽 `表.列` 引用（含 4 类误报过滤）
│   ├── sql.mjs               解析 INSERT 种子数据（支持同一表多条 INSERT）
│   ├── java.mjs              抽 @PreAuthorize 权限点
│   └── fs.mjs                文件遍历、命名转换、单复数
├── checkers/                 五道门禁，各导出 check(ctx) → Violation[]
└── test/
    ├── run.mjs               自测运行器（按目录名前缀：good* / bad* / must-fail*）
    └── fixtures/             每道门禁的夹具；must-fail* 验「解析失败必须响亮失败」
```

## 文档解析的 4 类误报过滤（② 的关键）

设计文档里 `a.b` 形式的东西远不止「表.列」一种：

| 类别 | 例子 | 过滤规则 |
|---|---|---|
| `sys_config` 键 | `security.refresh_grace_seconds`、`task.bid_visible_mode`（`task` 恰好也是表名！） | 与种子里**真实存在的 config_key 精确比对** |
| 文件名 | `pom.xml`、`app.json` | 后缀白名单 |
| 约束 / 索引名 | `ck_comm_rate`、`idx_geo` | 前缀白名单（`ck_`/`uk_`/`idx_`/`fk_`/`pk_`） |
| 历史引述 | §6.7 缺陷台账里「文档曾写了 `escrow.sub_amounts`」 | 跳过「缺陷审计台账」「变更记录」小节（用**标题栈**判父子，只记当前标题会失效） |

⛔ **不要用启发式**（比如"看起来像配置键就跳过"）。不精确的过滤会放过真缺陷；
精确规则即使要读种子数据，也比漏检划算。

## 已知未覆盖

| 项 | 说明 |
|---|---|
| `@PreAuthorize` 的**拼接/常量**形式 | 只认字面量。遇到非字面量会报 `unparseable-expression`，⛔ 不会静默放过 |
| 权限点**反向**检查 | 暂未检查「登记了但代码里没用」的权限点（会随功能开发自然补齐） |
| YAML 之外的接口来源 | 若将来有 gRPC / GraphQL，需另写检查器 |
| Java 源码解析 | 用正则而非 AST。对 `@PreAuthorize` 这种简单注解足够；若将来注解复杂化需换 AST |
