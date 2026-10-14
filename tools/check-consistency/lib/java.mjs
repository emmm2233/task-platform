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
/**
 * 识别 {@code @PreAuthorize}，**兼容全限定写法**。
 *
 * <p>⛔ 不能用字面量 {@code '@PreAuthorize'} 去 includes：
 * 写成 {@code @org.springframework.security.access.prepost.PreAuthorize(...)}
 * 时子串并不匹配 —— 开发者换个 import 风格就能静默绕过整道门禁。
 * （这不是假想：本检查器的第一次反向验证就因此漏检，注入的越权注解一个都没报。）
 */
const PRE_AUTHORIZE = /@(?:[A-Za-z_$][\w$]*\.)*PreAuthorize\b/;

export function parsePreAuthorize(javaSrcDirs) {
  const files = javaSrcDirs.flatMap((d) => readFiles(d, ['.java']));
  const found = [];
  const suspicious = [];

  for (const { file, text } of files) {
    // ⭐ 必须先剥掉注释再扫描。
    //    否则 Javadoc 里写一句"第 2 层权限点 —— @PreAuthorize + @EnableMethodSecurity"
    //    就会被当成一个「无法解析的注解」而报违规 ——
    //    这类假阳性会让团队很快把门禁关掉，比漏检更糟。
    const lines = stripComments(text).split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      if (!PRE_AUTHORIZE.test(lines[i])) {
        continue;
      }
      // 注解参数可能折行，往后看 3 行
      const window = lines.slice(i, i + 3).join(' ');
      const args = [...window.matchAll(/has(?:Any)?Authority\s*\(([^)]*)\)/g)];
      if (args.length === 0) {
        suspicious.push({ file, line: i + 1, text: lines[i].trim() });
        continue;
      }
      for (const a of args) {
        for (const q of a[1].matchAll(/'([^']+)'/g)) {
          found.push({ file, line: i + 1, code: q[1] });
        }
      }
    }
  }

  return { found, suspicious };
}

/**
 * 剥掉 Java 注释（行注释与块注释），但保留字符串字面量里的内容。
 *
 * <p>行结构不改变（块注释内部替换为等长空白并保留换行），
 * 这样报出来的行号仍然准确。
 */
function stripComments(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    const next = src[i + 1];

    // 行注释
    if (c === '/' && next === '/') {
      while (i < n && src[i] !== '\n') {
        out += ' ';
        i++;
      }
      continue;
    }
    // 块注释
    if (c === '/' && next === '*') {
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) {
        out += src[i] === '\n' ? '\n' : ' ';
        i++;
      }
      out += '  ';
      i += 2;
      continue;
    }
    // 字符串 / 字符字面量：原样保留（里面的 // 与 /* 不是注释）
    if (c === '"' || c === "'") {
      const quote = c;
      out += c;
      i++;
      while (i < n) {
        if (src[i] === '\\') {
          out += src[i] + (src[i + 1] ?? '');
          i += 2;
          continue;
        }
        out += src[i];
        if (src[i] === quote) {
          i++;
          break;
        }
        i++;
      }
      continue;
    }
    out += c;
    i++;
  }
  return out;
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
