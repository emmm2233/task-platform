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

  // 夹具目录按前缀决定"期望什么"，一套循环覆盖三类：
  //   good*       —— 必须 0 违规（否则是误报，团队会很快把门禁关掉）
  //   bad*        —— 必须 ≥1 违规（否则门禁是摆设）
  //   must-fail*  —— 必须【响亮地失败】：抛异常 或 ≥1 违规
  //                 ⭐ 专防"解析本身悄悄失败"：产物没解析出来 ⇒ 没东西可校验 ⇒ 零违规 ⇒ 绿灯。
  //                 实测过的真实形态：括号不配平让整张表从五道门禁里消失，
  //                 同一个"字段不存在"的注入从"报错"变成"0 违规"。
  const fixRoot = join(FIXTURES, name);
  const dirs = existsSync(fixRoot)
    ? readdirSync(fixRoot, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name).sort()
    : [];
  if (dirs.length === 0) {
    bad(`${name} 一个夹具都没有`);
  }

  for (const dirName of dirs) {
    const dir = join(fixRoot, dirName);
    const expectFailLoudly = dirName.startsWith('must-fail');
    let violations;
    try {
      violations = mod.check(contextFor(dir)) || [];
    } catch (e) {
      if (expectFailLoudly) {
        ok(`${name}/${dirName} 解析失败即硬错误`, `(抛异常：${String(e.message).slice(0, 56)}…)`);
      } else {
        bad(`${name}/${dirName} 执行抛异常`, e.message);
      }
      continue;
    }
    if (expectFailLoudly) {
      if (violations.length > 0) {
        ok(`${name}/${dirName} 拒绝静默通过`, `(报出 ${violations.length} 条，例：${violations[0].rule})`);
      } else {
        bad(`${name}/${dirName} 静默通过了 —— 解析失败被当成"检查通过"`);
      }
      continue;
    }
    if (dirName.startsWith('good')) {
      if (violations.length === 0) {
        ok(`${name}/${dirName} 零违规`);
      } else {
        bad(`${name}/${dirName} 出现误报（${violations.length} 条）`,
            violations.map(v => `${v.rule}: ${v.message}`).slice(0, 3).join('\n       '));
      }
    } else {
      if (violations.length > 0) {
        ok(`${name}/${dirName} 抓到违规`, `(${violations.length} 条，例：${violations[0].rule})`);
      } else {
        bad(`${name}/${dirName} 未抓到任何违规 —— 门禁形同虚设`);
      }
    }
  }

  console.log('');
}

console.log('─'.repeat(56));
console.log(`通过 ${passed} · 失败 ${failed}`);
process.exit(failed === 0 ? 0 : 1);
