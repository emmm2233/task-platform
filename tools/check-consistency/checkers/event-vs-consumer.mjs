import { readFiles, readJsonIfExists } from '../lib/fs.mjs';
import { extractInsertRows, unquote, parseJsonArrayValue } from '../lib/sql.mjs';

/**
 * 门禁 ⑤：事件 ↔ 消费方
 *
 * {@code sys_event_registry} 里登记的每个事件，其 producer 与 consumers
 * 都必须是真实存在的模块。
 *
 * ⭐ 这条抓的是「事件发出去了但没人消费」——
 *    最典型的后果是「中标了但订单没生成」这类静默断链：
 *    MQ 不报错、日志不报错，只是业务没往前走。这类故障排查成本极高。
 */
export function check(ctx) {
  const allow = readJsonIfExists(ctx.allowlistPath, {});
  const modules = new Set(allow.modules ?? []);
  const events = readEvents(readFiles(ctx.ddlDir, ['.sql']));
  const violations = [];

  if (events.length === 0) {
    // ⛔ 读不到事件 = 这道门禁没在工作，必须报出来而不是静默通过
    violations.push({
      rule: 'event-vs-consumer/registry-empty',
      file: ctx.ddlDir,
      message: '未能从种子 SQL 里读到任何 sys_event_registry 记录',
      hint: '检查 INSERT 语句格式，或确认事件注册表是否已种子化',
    });
    return violations;
  }

  for (const e of events) {
    const code = e.event_code || e.eventCode || '(未命名事件)';

    const producer = unquote(e.producer);
    if (producer && modules.size > 0 && !modules.has(producer)) {
      violations.push({
        rule: 'event-vs-consumer/unknown-producer',
        file: `${ctx.ddlDir} (${code})`,
        message: `事件的 producer「${producer}」不在模块白名单内`,
        hint: '改正模块名，或把新模块加入 allowlist.json 的 modules',
      });
    }

    const consumers = parseJsonArrayValue(e.consumers);
    if (consumers.length === 0) {
      violations.push({
        rule: 'event-vs-consumer/no-consumer',
        file: `${ctx.ddlDir} (${code})`,
        message: `事件 ${code} 没有任何消费方`,
        hint: '⭐ 没有消费方的事件等于静默断链：业务不会往前走，且不报错',
      });
      continue;
    }
    for (const c of consumers) {
      if (modules.size > 0 && !modules.has(c)) {
        violations.push({
          rule: 'event-vs-consumer/unknown-consumer',
          file: `${ctx.ddlDir} (${code})`,
          message: `事件 ${code} 的消费方「${c}」不在模块白名单内`,
          hint: '改正模块名，或把新模块加入 allowlist.json 的 modules',
        });
      }
    }
  }

  return violations;
}

/** 从所有种子 SQL 里收集 sys_event_registry 记录（表可能被拆在多条 INSERT 里） */
function readEvents(sqlSources) {
  const out = [];
  for (const { text } of sqlSources) {
    const { rows } = extractInsertRows(text, 'sys_event_registry');
    out.push(...rows);
  }
  return out;
}
