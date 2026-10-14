import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join } from 'node:path';

/** 递归读取目录下所有匹配扩展名的文件，返回 [{file, text}] */
export function readFiles(dir, exts, out = []) {
  if (!existsSync(dir)) {
    return out;
  }
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (name === 'node_modules' || name === '.git' || name === 'target' || name === 'dist') {
        continue;
      }
      readFiles(p, exts, out);
    } else if (exts.some((e) => name.endsWith(e))) {
      out.push({ file: p, text: readFileSync(p, 'utf8').replace(/^\uFEFF/, '') });
    }
  }
  return out;
}

export function readJsonIfExists(path, fallback) {
  if (!existsSync(path)) {
    return fallback;
  }
  return JSON.parse(readFileSync(path, 'utf8').replace(/^\uFEFF/, ''));
}

/** camelCase / PascalCase → snake_case */
export function toSnake(s) {
  return s
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1_$2')
    .toLowerCase();
}

/** snake_case → PascalCase */
export function toPascal(s) {
  return s
    .split(/[_\-\s]+/)
    .filter(Boolean)
    .map((w) => w[0].toUpperCase() + w.slice(1))
    .join('');
}

/** 简单复数（够用于表名匹配） */
export function pluralize(w) {
  if (/(s|x|z|ch|sh)$/.test(w)) {
    return w + 'es';
  }
  if (/[^aeiou]y$/.test(w)) {
    return w.slice(0, -1) + 'ies';
  }
  return w + 's';
}

export function singularize(w) {
  if (/ies$/.test(w)) {
    return w.slice(0, -3) + 'y';
  }
  if (/(ses|xes|zes|ches|shes)$/.test(w)) {
    return w.slice(0, -2);
  }
  if (/s$/.test(w) && !/ss$/.test(w)) {
    return w.slice(0, -1);
  }
  return w;
}

/** 相对路径展示，日志更短 */
export function rel(p, root) {
  if (!root) {
    return p;
  }
  const a = p.replace(/\\/g, '/');
  const b = root.replace(/\\/g, '/').replace(/\/$/, '');
  return a.startsWith(b) ? a.slice(b.length + 1) : a;
}
