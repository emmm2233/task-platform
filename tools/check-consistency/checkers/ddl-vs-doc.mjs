import { readFiles, readJsonIfExists } from '../lib/fs.mjs';
import { parseDdl } from '../lib/ddl.mjs';
import { parseDocRefs } from '../lib/docs.mjs';
import { extractInsertRows, unquote } from '../lib/sql.mjs';

/**
 * 门禁 ②：DDL ↔ 文档
 *
 * 文档里以 `表.字段` 形式出现的引用，必须在 DDL 里有定义。
 *
 * ⭐⭐ 这条就是为 §6.7 的 P0-① 立的：
 *    当时文档 §5.5.3 写「记账单位是子订单份额（`escrow.sub_amounts`）」，
 *    而 escrow 表根本没有这一列 —— 单看文档自洽、单看 DDL 也自洽，
 *    **矛盾只在两者之间**。人工评审两次都没发现。
 *
 * ⚠️ 为控制误报，先按精确规则排除「配置键 / 文件名 / 约束名 / 历史引述」，
 *    详见 lib/docs.mjs 的注释。误报多的门禁一定会被关掉。
 */
/**
 * ⚠️ 文件名后缀：形如 curl.exe / check.mjs / app.jar 的引用会被「表.列」提取器
 * 误当成"某表的某列"，报 unknown-table。
 *
 * ⭐ 为什么用"认识扩展名"而不是把 curl 登记进 allowlist：
 *   扩展名是**有限且稳定**的集合，一次写清就永久消除这一整类误报；
 *   而 allowlist 是逐个累加的 —— 每提到一个新工具就要加一条，
 *   且加了之后如果有人真建了张叫 curl 的表，反而被静默放过。
 *
 * 前车之鉴见 T3：门禁 ④ 曾把 Javadoc 里的 @PreAuthorize 当成真实注解（误报），
 * 也曾因全限定写法 @org.springframework...PreAuthorize 而漏检（静默失效）。
 * 误报与漏检都要治，但**误报多的门禁一定会被关掉**，所以优先治误报。
 */
const FILE_SUFFIXES = new Set([
  'exe', 'cmd', 'bat', 'ps1', 'sh', 'jar', 'war',
  'js', 'mjs', 'cjs', 'ts', 'vue', 'java', 'sql',
  'json', 'yaml', 'yml', 'xml', 'properties', 'toml',
  'md', 'txt', 'log', 'html', 'css', 'png', 'jpg', 'svg',
]);

export function check(ctx) {
  const allow = readJsonIfExists(ctx.allowlistPath, {});
  const allowed = normalizeAllowlist(allow.docRefAllowlist ?? []);

  const sqlSources = readFiles(ctx.ddlDir, ['.sql']);
  const ddl = parseDdl(sqlSources);
  const configKeys = collectConfigKeys(sqlSources);

  const { refs, skipped } = parseDocRefs(ctx.docsDir, {
    configKeys,
    // ⚠️ 根目录文档也要扫：只扫 docs/ 会漏掉 README.md
    extraDocFiles: ctx.extraDocFiles ?? [],
  });
  const violations = [];

  // ⛔ 一条引用都没抽到 = 这道门禁实际上没在工作。
  //   触发方式（都不是假设）：把引用写进 ``` 代码块、去掉反引号、改成大写，
  //   或者 extraDocFiles 配置丢了 —— 此时 refs 为空、零违规、绿灯。
  //   ⭐ 与门禁① 的 nothing-checked、门禁⑤ 的 registry-empty 同一原则：
  //     "没检查到"必须报出来，而不是当成"检查通过"。
  if (refs.length === 0) {
    violations.push({
      rule: 'ddl-vs-doc/nothing-checked',
      file: ctx.docsDir,
      message: '文档里一条 `表.列` 引用都没有抽到 —— 这道门禁实际上没在工作',
      hint: '检查文档写法（需反引号包裹的小写 表.列）、ctx.docsDir 与 extraDocFiles 配置；'
          + '门禁静默失效比误报更危险',
    });
  }

  for (const r of refs) {
    const key = `${r.table}.${r.column}`;
    // ⚠️ 文件名而非“表.列”：curl.exe / check.mjs / app.jar …（否则全是误报）
    if (FILE_SUFFIXES.has(String(r.column).toLowerCase())) {
      continue;
    }
    if (isAllowed(allowed, key, r.file)) {
      continue;
    }
    const table = ddl.tables.get(r.table);
    if (!table) {
      violations.push({
        rule: 'ddl-vs-doc/unknown-table',
        file: `${r.file}:${r.line}`,
        message: `文档引用了不存在的表 ${r.table}（原文：${key}）`,
        hint: '若确为概念性引用/配置键，请修正写法或登记到 allowlist.json 的 docRefAllowlist',
      });
      continue;
    }
    if (!table.columns.includes(r.column)) {
      violations.push({
        rule: 'ddl-vs-doc/unknown-column',
        file: `${r.file}:${r.line}`,
        message: `表 ${r.table} 没有列 ${r.column}（该表实际列：${table.columns.slice(0, 12).join(', ')}${table.columns.length > 12 ? ' …' : ''}）`,
        hint: '⭐ 文档与 DDL 矛盾：要么改文档，要么补迁移 —— 二者必须一致',
      });
    }
  }

  // 把跳过情况附带出来（用非违规字段承载，便于 CI 打印统计）
  Object.defineProperty(violations, 'skipped', { value: skipped, enumerable: false });
  return violations;
}

/** 收集种子数据里登记的全部 sys_config 键，用于精确排除"配置键被误当成表.列" */
function collectConfigKeys(sqlSources) {
  const keys = new Set();
  for (const { text } of sqlSources) {
    const { rows } = extractInsertRows(text, 'sys_config');
    for (const row of rows) {
      const k = unquote(row.config_key ?? row.configKey ?? '');
      if (k) {
        keys.add(k);
      }
    }
  }
  return keys;
}

/**
 * 白名单归一化。支持两种写法：
 *   "escrow.sub_amounts"                                    —— 全局放行（慎用）
 *   { "ref": "escrow.sub_amounts",
 *     "allowIn": ["docs/plans/**"],
 *     "reason": "T3 任务说明里对 P0-① 缺陷的描述性引用" }     —— ⭐ 按文件范围限定
 *
 * 优先用第二种：全局放行会让同名的**未来真实引用**也被放过，
 * 而"描述某个不存在的列"与"声称某个列存在"是两回事，只有按位置限定才分得清。
 */
function normalizeAllowlist(entries) {
  return entries.map((e) =>
    typeof e === 'string'
      ? { ref: e, allowIn: null, reason: '（未注明原因）' }
      : { ref: e.ref, allowIn: e.allowIn ?? null, reason: e.reason ?? '（未注明原因）' },
  );
}

function isAllowed(allowed, key, file) {
  // ⚠️ 不要用 ^...$ 锚定：r.file 是**绝对路径**（含盘符与中文目录），
  //    锚定后 `docs/plans/**` 这种相对写法永远匹配不上。用"路径包含"判断。
  const norm = file.replace(/\\/g, '/');
  for (const a of allowed) {
    if (a.ref !== key) {
      continue;
    }
    if (!a.allowIn) {
      return true; // 全局放行
    }
    for (const pat of a.allowIn) {
      const re = new RegExp(
        pat.replace(/\\/g, '/').replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*\*/g, '.*').replace(/\*/g, '[^/]*'),
      );
      if (re.test(norm)) {
        return true;
      }
    }
  }
  return false;
}
