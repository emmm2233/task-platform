/**
 * 一致性门禁的自测运行器。
 *
 * 每个检查器都有 good / bad 两组夹具：
 *   - good/  必须 0 违规（否则门禁会误报，团队会很快把它关掉）
 *   - bad/   必须 ≥1 违规（否则门禁是摆设）
 *
 * ⭐ 「bad 组必须报错」比「good 组通过」重要得多 ——
 *    我们真正要防的是**门禁悄悄失效**，而不是它误报。
 */
import { readdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { pathToFileURL } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const FIXTURES = join(__dirname, 'fixtures');

const CHECKERS = [
  'ddl-vs-openapi',
  'ddl-vs-doc',
  'openapi-vs-ddl',
  'perm-vs-code',
  'event-vs-consumer',
];

let passed = 0;
let failed = 0;

function ok(name, detail) {
  passed++;
  console.log(`  ✅ ${name}${detail ? '  ' + detail : ''}`);
}

function bad(name, detail) {
  failed++;
  console.log(`  ❌ ${name}${detail ? '\n       ' + detail : ''}`);
}

/** 为一个夹具目录构造上下文。夹具目录里按需放 db/ api/ docs/ src/ 子目录。 */
function contextFor(dir) {
  return {
    root: dir,
    ddlDir: join(dir, 'db', 'migration'),
    openapiFile: join(dir, 'api', 'openapi.yaml'),
    docsDir: join(dir, 'docs'),
    javaSrcDirs: [join(dir, 'src')],
    seedFiles: [join(dir, 'db', 'migration', 'V4__seed_data.sql')],
    allowlistPath: join(ROOT, 'allowlist.json'),
    extraDocFiles: [],
    moduleWhitelistPath: join(ROOT, 'allowlist.json'),
  };
}

console.log('一致性门禁自测\n');

for (const name of CHECKERS) {
  console.log(`【${name}】`);
  let mod;
  try {
    mod = await import(pathToFileURL(join(ROOT, 'checkers', `${name}.mjs`)).href);
  } catch (e) {
    bad(`${name} 检查器无法加载`, e.message);
    continue;
  }
  if (typeof mod.check !== 'function') {
    bad(`${name} 未导出 check(ctx) 函数`);
    continue;
  }

  for (const kind of ['good', 'bad']) {
    const dir = join(FIXTURES, name, kind);
    if (!existsSync(dir)) {
      bad(`${name}/${kind} 夹具缺失`);
      continue;
    }
    let violations;
    try {
      violations = mod.check(contextFor(dir)) || [];
    } catch (e) {
      bad(`${name}/${kind} 执行抛异常`, e.message);
      continue;
    }
    if (kind === 'good') {
      if (violations.length === 0) {
        ok(`${name}/good 零违规`);
      } else {
        bad(`${name}/good 出现误报（${violations.length} 条）`,
            violations.map(v => `${v.rule}: ${v.message}`).slice(0, 3).join('\n       '));
      }
    } else {
      if (violations.length > 0) {
        ok(`${name}/bad 抓到违规`, `(${violations.length} 条，例：${violations[0].rule})`);
      } else {
        bad(`${name}/bad 未抓到任何违规 —— 门禁形同虚设`);
      }
    }
  }
  console.log('');
}

console.log('─'.repeat(56));
console.log(`通过 ${passed} · 失败 ${failed}`);
process.exit(failed === 0 ? 0 : 1);
