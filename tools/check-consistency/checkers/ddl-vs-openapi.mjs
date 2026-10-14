import { readFiles, readJsonIfExists, toSnake } from '../lib/fs.mjs';
import { parseDdl } from '../lib/ddl.mjs';
import { parseOpenApi } from '../lib/openapi.mjs';

/**
 * 门禁 ①：DDL ↔ OpenAPI
 *
 * ⭐ 校验规则（精确版）：
 *   仅当 **schema 名能直接映射到一张真实表** 时才严格校验
 *   （`CreditLog` → `credit_log`、`WalletTxn` → `wallet_txn` …），
 *   此时该 schema 的**每个属性都必须是那张表的列**。
 *
 * 为什么不对全部 schema 一视同仁：
 *   响应体里大量字段是计算/联表得来的（`phoneMasked`、`categoryName`、
 *   `publisherNickname`…）。若要求它们都在库里存在，就得把几十个名字塞进白名单 ——
 *   **白名单一旦变成垃圾桶，门禁就死了**。而视图模型本来就不该被"字段必须落库"约束。
 *
 * 这样做反而**更严**：不只要求字段存在，还要求它属于**正确的那张表**。
 * （早期版本只检查"是否存在于任意表"，会把 `credit_log.id` 与 `task.id` 混为一谈。）
 *
 * 计算字段可在两处声明：
 *   - OpenAPI schema 内的 `x-computed: [字段名]`（就近声明，推荐）
 *   - allowlist.json 的 computedFields（跨 schema 通用的，如 statusName）
 */
export function check(ctx) {
  const allow = readJsonIfExists(ctx.allowlistPath, {});
  const globalComputed = new Set(allow.computedFields ?? []);

  const ddl = parseDdl(readFiles(ctx.ddlDir, ['.sql']));
  const api = parseOpenApi(ctx.openapiFile);
  const violations = [];
  let checkedSchemas = 0;

  for (const [schemaName, { props, schema }] of api.schemas) {
    const tableName = resolveTable(schemaName, schema, ddl);
    if (!tableName) {
      continue; // 视图模型 / DTO / 枚举 —— 不适用本规则
    }
    const table = ddl.tables.get(tableName);
    checkedSchemas++;

    const localComputed = new Set(schema?.['x-computed'] ?? []);
    const fieldMap = schema?.['x-field-map'] ?? {};
    for (const prop of props) {
      if (globalComputed.has(prop) || localComputed.has(prop)) {
        continue;
      }
      // 主键别名：表主键叫 `id`，接口按实体叫 `unitId` / `ticketId` / `sessionId`
      const mapped = fieldMap[prop];
      if (mapped) {
        if (!table.columns.includes(mapped)) {
          violations.push({
            rule: 'ddl-vs-openapi/bad-field-map',
            file: ctx.openapiFile,
            message: `${schemaName} 的 x-field-map 把 ${prop} 映射到 ${mapped}，但表 ${tableName} 没有这一列`,
            hint: '改正映射目标',
          });
        }
        continue;
      }
      const snake = toSnake(prop);
      if (!table.columns.includes(snake)) {
        violations.push({
          rule: 'ddl-vs-openapi/unknown-field',
          file: ctx.openapiFile,
          message: `${schemaName}（→ 表 ${tableName}）的属性 ${prop}（→ ${snake}）不是该表的列`,
          hint: `若为计算字段，在 schema 下加 x-computed: [${prop}]；`
              + `若为主键别名，加 x-field-map: { ${prop}: <真实列名> }；若是拼写错，改 OpenAPI 或补迁移`,
        });
      }
    }
  }

  if (checkedSchemas === 0) {
    // ⛔ 一个都没校验 = 这道门禁没在工作，必须报出来而不是静默通过
    violations.push({
      rule: 'ddl-vs-openapi/nothing-checked',
      file: ctx.openapiFile,
      message: '没有任何 schema 被解析到真实表 —— 这道门禁实际上没在工作',
      hint: '检查 schema 命名与 x-table 标注；门禁静默失效比误报更危险',
    });
  }

  return violations;
}

/**
 * schema 名 → 真实表名。三种途径，按优先级：
 *   ① 显式 `x-table` 标注（最可靠，用于名字对不上的情况）
 *   ② 名字直接匹配（`CreditLog` → `credit_log`）
 *   ③ 剥掉常见视图后缀后再匹配（`EscrowInfo` → `escrow`、`TaskSummary` → `task`）
 *
 * ⭐ ③ 是必需的：没有它，`EscrowInfo` 会被解析成不存在的 `escrow_info` 而被当成视图模型跳过，
 *    于是**它的字段拼错也检查不出来** —— 本门禁第一版就栽在这上面，
 *    靠"注入一个拼写错看门禁是否报错"的反向验证才发现。
 */
function resolveTable(schemaName, schema, ddl) {
  const explicit = schema?.['x-table'];
  if (explicit) {
    return ddl.tables.has(explicit) ? explicit : null;
  }
  const direct = toSnake(schemaName);
  if (ddl.tables.has(direct)) {
    return direct;
  }
  for (const suffix of VIEW_SUFFIXES) {
    if (schemaName.endsWith(suffix) && schemaName.length > suffix.length) {
      const base = toSnake(schemaName.slice(0, -suffix.length));
      if (ddl.tables.has(base)) {
        return base;
      }
    }
  }
  return null;
}

/**
 * 视图模型后缀：剥掉后再看是否命中真实表。
 *
 * ⛔ 只收「实体视图」后缀，**不要**收 `Request` / `Result` / `Response` / `Cmd`：
 *    那些是**操作 DTO**（`AwardResult` 是中标操作的结果，不是 award 表的视图），
 *    剥掉后缀会得到错误的表映射并产生大量误报。
 *    若某个请求 DTO 确实与某张表 1:1，请在该 schema 上显式写 `x-table`。
 */
const VIEW_SUFFIXES = [
  'Info', 'Summary', 'Detail', 'Details', 'Profile', 'Record', 'Item', 'Vo', 'VO', 'Dto', 'DTO',
];
