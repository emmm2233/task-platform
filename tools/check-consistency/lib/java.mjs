import { readFiles } from './fs.mjs';

/**
 * 从 Java 源码里抽出所有 {@code @PreAuthorize} 声明的权限点。
 *
 * 支持两种写法：
 *   @PreAuthorize("hasAuthority('account:user:freeze')")
 *   @PreAuthorize("hasAnyAuthority('a:b:c','d:e:f')")
 *
 * ⛔ 只认字面量。若有人写成拼接或常量引用，本检查器会<b>漏掉</b>它 ——
 *    所以同时统计「用了表达式但没抽到权限点」的情况并报警，避免门禁静默失效。
 */
export function parsePreAuthorize(javaSrcDirs) {
  const files = javaSrcDirs.flatMap((d) => readFiles(d, ['.java']));
  const found = [];
  const suspicious = [];

  for (const { file, text } of files) {
    const lines = text.split(/\r?\n/);
    lines.forEach((line, i) => {
      if (!line.includes('@PreAuthorize')) {
        return;
      }
      const args = [...line.matchAll(/has(?:Any)?Authority\s*\(([^)]*)\)/g)];
      if (args.length === 0) {
        suspicious.push({ file, line: i + 1, text: line.trim() });
        return;
      }
      for (const a of args) {
        for (const q of a[1].matchAll(/'([^']+)'/g)) {
          found.push({ file, line: i + 1, code: q[1] });
        }
      }
    });
  }

  return { found, suspicious };
}

/** 从种子 SQL 里抽 sys_permission 的权限点 */
export function parsePermissions(sqlSources) {
  const codes = new Map();
  for (const { file, text } of sqlSources) {
    const re = /INSERT\s+INTO\s+`sys_permission`\s*\(([^)]*)\)\s*VALUES/gi;
    let m;
    while ((m = re.exec(text)) !== null) {
      const cols = m[1].split(',').map((c) => c.trim().replace(/`/g, ''));
      const codeIdx = cols.indexOf('perm_code');
      const end = text.indexOf(';', m.index);
      const body = text.slice(m.index + m[0].length, end < 0 ? text.length : end);
      for (const q of body.matchAll(/'([a-z][a-z0-9_]*(?::[a-z0-9_]+)+)'/g)) {
        if (codeIdx >= 0) {
          codes.set(q[1], { file, code: q[1] });
        }
      }
    }
  }
  return codes;
}
