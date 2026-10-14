/**
 * 跨产物一致性门禁 —— 入口。
 *
 * 用法：
 *   node tools/check-consistency/check.mjs            # 检查真实产物
 *   node tools/check-consistency/check.mjs --warn     # 违规只警告不失败（本地调试用）
 *
 * ⭐ 为什么要在写业务代码之前就位：
 *   §6.7 的 P0-①（文档写 `escrow.sub_amounts`、DDL 里没有）单看任一份产物都自洽，
 *   只有把两份摆在一起逐字段比对才会暴露。这类矛盾靠人工评审抓不住，
 *   必须在 CI 里自动拦。
 */
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(__dirname, '..', '..');

const ctx = {
  root: REPO,
  ddlDir: join(REPO, 'db', 'migration'),
  openapiFile: join(REPO, 'api', 'openapi.yaml'),
  docsDir: join(REPO, 'docs'),
  javaSrcDirs: [
    join(REPO, 'platform-app', 'src', 'main', 'java'),
    join(REPO, 'platform-common', 'src', 'main', 'java'),
  ],
  allowlistPath: join(__dirname, 'allowlist.json'),
  // ⚠️ 仓库根的单篇文档也要纳入扫描，否则 README.md 里的引用会漏检
  extraDocFiles: [join(REPO, 'README.md'), join(REPO, 'deploy', 'README.md')],
};

const CHECKERS = [
  ['ddl-vs-openapi', '① DDL ↔ OpenAPI：接口字段必须在库里有定义'],
  ['ddl-vs-doc', '② DDL ↔ 文档：文档里的 `表.列` 引用必须存在（P0-① 那道门）'],
  ['openapi-vs-ddl', '③ OpenAPI ↔ DDL：每张表都要有接口读写或声明为内部表'],
  ['perm-vs-code', '④ 权限点 ↔ 代码：@PreAuthorize 的权限点必须已登记'],
  ['event-vs-consumer', '⑤ 事件 ↔ 消费方：producer/consumers 必须是真实模块'],
];

const warnOnly = process.argv.includes('--warn');

let total = 0;
const byRule = new Map();

console.log('跨产物一致性门禁');
console.log('='.repeat(72));

for (const [name, title] of CHECKERS) {
  const mod = await import(`./checkers/${name}.mjs`);
  let violations;
  try {
    violations = mod.check(ctx) ?? [];
  } catch (e) {
    violations = [{
      rule: `${name}/checker-crashed`,
      file: name,
      message: `检查器抛异常：${e.message}`,
      hint: '门禁本身坏了 —— 修它，不要跳过',
    }];
  }
  total += violations.length;
  for (const v of violations) {
    byRule.set(v.rule, (byRule.get(v.rule) ?? 0) + 1);
  }

  const mark = violations.length === 0 ? '✅' : '❌';
  console.log(`\n${mark} ${title}`);
  if (violations.length === 0) {
    console.log('     零违规');
    continue;
  }
  for (const v of violations.slice(0, 40)) {
    console.log(`     · [${v.rule}] ${v.message}`);
    console.log(`       位置：${v.file}`);
    if (v.hint) {
      console.log(`       处理：${v.hint}`);
    }
  }
  if (violations.length > 40) {
    console.log(`     … 另有 ${violations.length - 40} 条同类违规（输出已截断）`);
  }
}

console.log('\n' + '='.repeat(72));
if (total === 0) {
  console.log('✅ 五道门禁全部通过 · 0 违规');
  process.exit(0);
}

console.log(`❌ 共 ${total} 条违规，按规则分布：`);
for (const [rule, n] of [...byRule].sort((a, b) => b[1] - a[1])) {
  console.log(`   ${String(n).padStart(4)}  ${rule}`);
}
process.exit(warnOnly ? 0 : 1);
