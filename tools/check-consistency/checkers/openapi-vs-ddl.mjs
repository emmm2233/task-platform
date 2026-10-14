import { readFiles, readJsonIfExists, toPascal, pluralize, singularize } from '../lib/fs.mjs';
import { parseDdl } from '../lib/ddl.mjs';
import { parseOpenApi } from '../lib/openapi.mjs';

/**
 * 门禁 ③：OpenAPI ↔ DDL
 *
 * 每张表都应当至少被一个接口读写；否则必须在 allowlist 的 internalTables 里
 * **显式声明为内部表**。
 *
 * ⭐ 这条抓的是「建了表但忘了做接口」和「做了表却没人用」——
 *    前者让功能没法交付，后者是设计冗余。两种情况都应该被看见，而不是默默存在。
 */
export function check(ctx) {
  const allow = readJsonIfExists(ctx.allowlistPath, {});
  const internal = new Map(
    (allow.internalTables ?? []).map((e) =>
      typeof e === 'string' ? [e, '（未注明原因）'] : [e.table, e.reason ?? '（未注明原因）'],
    ),
  );
  /** 名称匹配不到、但人工确认已被接口覆盖的表 */
  const coveredBy = allow.coveredBy ?? {};

  const ddl = parseDdl(readFiles(ctx.ddlDir, ['.sql']));
  const api = parseOpenApi(ctx.openapiFile);
  const violations = [];

  // 建归一化语料：把路径、schema 名、属性名、operationId、tag 全部摊平成一个下划线分隔的大串，
  // 这样可以统一处理「连字符 vs 下划线」「camelCase vs snake_case」「单复数」三种差异。
  const haystack = buildHaystack(api);

  for (const name of ddl.tables.keys()) {
    if (internal.has(name)) {
      continue;
    }
    if (coveredBy[name]) {
      continue;
    }
    if (coverage(name, api, haystack)) {
      continue;
    }
    violations.push({
      rule: 'openapi-vs-ddl/uncovered-table',
      file: ctx.openapiFile,
      message: `表 ${name} 没有任何接口读写，也未声明为内部表`,
      hint: '补接口；若名称匹配不到但确实被某接口使用，登记到 allowlist.json 的 coveredBy；'
          + '若属内部表，登记到 internalTables 并写明原因',
    });
  }

  return violations;
}

/** 归一化：camelCase → 下划线；连字符/点/空格 → 下划线；全小写 */
function normalize(s) {
  return s
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1_$2')
    .toLowerCase()
    .replace(/[-.\s]+/g, '_');
}

function buildHaystack(api) {
  const parts = [];
  for (const p of api.paths) {
    parts.push(normalize(p));
  }
  for (const [name, { props }] of api.schemas) {
    parts.push(normalize(name));
    // ⭐ 把属性名也算进来：若某接口返回 `shares` 数组或 `timeline` 数组，
    //    说明 escrow_share / dispute_timeline 的数据是**可达的**，就应算被覆盖。
    //    覆盖的判定标准是"数据能否被端拿到"，而不是"有没有以表名命名的路径"。
    for (const prop of props) {
      parts.push(normalize(prop));
    }
  }
  const raw = api.raw ?? {};
  for (const item of Object.values(raw.paths ?? {})) {
    for (const [method, op] of Object.entries(item ?? {})) {
      if (op && typeof op === 'object') {
        if (op.operationId) {
          parts.push(normalize(op.operationId));
        }
        for (const t of op.tags ?? []) {
          parts.push(normalize(t));
        }
      }
      parts.push(normalize(method));
    }
  }
  return '_' + parts.join('_') + '_';
}

/** 表名 → 候选词形（去前缀、单复数、末段词、去末段词） */
function variantsOf(table) {
  const out = new Set([table, pluralize(table), singularize(table)]);
  const words = table.split('_');

  for (const prefix of ['sys_', 'biz_', 't_']) {
    if (table.startsWith(prefix)) {
      const stripped = table.slice(prefix.length);
      out.add(stripped);
      out.add(pluralize(stripped));
      out.add(singularize(stripped));
    }
  }
  // 末段词：task_bid → bid / bids；im_message → message / messages
  const last = words[words.length - 1];
  if (last && last.length >= 3) {
    out.add(last);
    out.add(pluralize(last));
  }
  // 去末段词：approval_record → approval / approvals
  // （明细/子表通常借用父实体的接口，如 vote 写 approval_record）
  if (words.length >= 2) {
    const head = words.slice(0, -1).join('_');
    out.add(head);
    out.add(pluralize(head));
  }
  return [...out];
}

/** 表是否被接口覆盖：schema 名精确命中，或归一化语料里出现候选词形 */
function coverage(table, api, haystack) {
  if (api.schemas.has(toPascal(table))) {
    return 'schema';
  }
  for (const v of variantsOf(table)) {
    if (haystack.includes(`_${v}_`) || haystack.includes(`_${v}s_`)) {
      return 'token:' + v;
    }
  }
  return null;
}
