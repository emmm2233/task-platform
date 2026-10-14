/**
 * 合并 openapi.yaml 中被拆开的重复 path 键。
 *
 * 背景：同一路径的不同 HTTP 方法被写成了两个 `/path:` 键。
 * YAML 禁止重复键 —— 后一个会**完全覆盖**前一个，
 * 于是先写的那些方法在解析后彻底消失（Swagger 也看不出来）。
 *
 * 做法：把同一路径的所有方法块合并到**第一次出现的位置**，删掉后面的键行。
 */
import { readFileSync, writeFileSync } from 'node:fs';

const FILE = process.argv[2];
if (!FILE) {
  console.error('用法: node merge-paths.mjs <openapi.yaml>');
  process.exit(2);
}

const lines = readFileSync(FILE, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/);
const EOL = readFileSync(FILE, 'utf8').includes('\r\n') ? '\r\n' : '\n';

const PATH_KEY = /^  (\/[A-Za-z0-9_/{}.-]*)\s*:\s*$/;

// 1) 找出 paths 段的范围
let pathsIdx = lines.findIndex((l) => /^paths\s*:\s*$/.test(l));
if (pathsIdx < 0) {
  console.error('❌ 未找到 paths: 段');
  process.exit(1);
}
// paths 段在下一个顶层键（非缩进）处结束
let pathsEnd = lines.length;
for (let i = pathsIdx + 1; i < lines.length; i++) {
  if (/^[A-Za-z]/.test(lines[i])) {
    pathsEnd = i;
    break;
  }
}

// 2) 收集所有 path 键的位置（仅在 paths 段内）
const keyPositions = [];
for (let i = pathsIdx + 1; i < pathsEnd; i++) {
  const m = lines[i].match(PATH_KEY);
  if (m) {
    keyPositions.push({ line: i, key: m[1] });
  }
}

// 3) 切出每个 path 的块 [start, end)
const blocks = keyPositions.map((k, idx) => ({
  key: k.key,
  keyLine: k.line,
  start: k.line + 1,
  end: idx + 1 < keyPositions.length ? keyPositions[idx + 1].line : pathsEnd,
}));

// 4) 按 key 分组；重复的合并
const seen = new Map();
const toDelete = [];
const merged = new Map(); // key → 合并后的内容行数组

for (const b of blocks) {
  const body = lines.slice(b.start, b.end);
  // 去掉尾部空行，合并时统一补
  while (body.length && body[body.length - 1].trim() === '') {
    body.pop();
  }
  if (!seen.has(b.key)) {
    seen.set(b.key, b);
    merged.set(b.key, { keyLine: b.keyLine, body: [...body] });
  } else {
    // 追加到首次出现处，并标记这一块整体删除
    const m = merged.get(b.key);
    m.body.push(...body);
    toDelete.push({ start: b.keyLine, end: b.end });
  }
}

if (toDelete.length === 0) {
  console.log('✅ 没有重复的 path 键，无需合并');
  process.exit(0);
}

// 5) 重建文件：先输出到 paths 段之前，再按合并结果输出 paths 段
const out = [];
out.push(...lines.slice(0, pathsIdx + 1));

// 依首次出现顺序输出
const order = blocks.filter((b, i) => blocks.findIndex((x) => x.key === b.key) === i).map((b) => b.key);
for (const key of order) {
  const m = merged.get(key);
  out.push(`  ${key}:`);
  out.push(...m.body);
}

// paths 段之后的内容（跳过被删除的块）
const deletedRanges = toDelete.map((d) => ({ start: d.start, end: d.end }));
let i = pathsEnd;
while (i < lines.length) {
  const del = deletedRanges.find((d) => i >= d.start && i < d.end);
  if (del) {
    i = del.end;
    continue;
  }
  out.push(lines[i]);
  i++;
}

writeFileSync(FILE, out.join(EOL), 'utf8');
console.log(`✅ 已合并 ${toDelete.length} 处重复 path 键`);
for (const d of toDelete) {
  console.log(`   删除行 ${d.start + 1}-${d.end}（已并入首次出现处）`);
}
