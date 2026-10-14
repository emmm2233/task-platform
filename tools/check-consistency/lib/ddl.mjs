/**
 * 解析 Flyway 迁移脚本里的 DDL。
 *
 * 只需要拿到「有哪些表、每张表有哪些列、有哪些索引/约束」——
 * 一致性门禁不关心类型与注释，那是 DDL 自己的事。
 */

const IDENT = '`([^`]+)`';

/** 不是列定义的行首关键字 */
const NON_COLUMN = /^(PRIMARY\s+KEY|UNIQUE\s+KEY|UNIQUE\s+INDEX|KEY|INDEX|CONSTRAINT|CHECK|FOREIGN\s+KEY|FULLTEXT)\b/i;

/**
 * @param {string[]} sources 形如 [{file, text}]
 * @returns {{tables: Map<string, {name:string,file:string,columns:string[],indexes:string[],constraints:string[]}>}}
 */
export function parseDdl(sources) {
  const tables = new Map();

  for (const { file, text } of sources) {
    // 去掉行注释（-- 到行尾），避免注释里的括号干扰括号配对
    const cleaned = stripLineComments(text);

    const re = new RegExp(`CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?${IDENT}\\s*\\(`, 'gi');
    let m;
    while ((m = re.exec(cleaned)) !== null) {
      const name = m[1];
      const bodyStart = m.index + m[0].length;
      const bodyEnd = matchParen(cleaned, bodyStart - 1);
      if (bodyEnd < 0) {
        continue;
      }
      const body = cleaned.slice(bodyStart, bodyEnd);

      const columns = [];
      const indexes = [];
      const constraints = [];

      for (const rawLine of splitTopLevel(body)) {
        const line = rawLine.trim();
        if (!line) {
          continue;
        }
        if (NON_COLUMN.test(line)) {
          // ⚠️ flags 必须作为第二个参数传入：new RegExp(`...`i) 是无效语法
          const ix = line.match(new RegExp(`^(?:UNIQUE\\s+KEY|UNIQUE\\s+INDEX|KEY|INDEX)\\s+${IDENT}`, 'i'));
          if (ix) {
            indexes.push(ix[1]);
            continue;
          }
          const ck = line.match(/^CONSTRAINT\s+`([^`]+)`/i) || line.match(/^CHECK\s*\(/i);
          if (ck) {
            constraints.push(ck[1] || 'ANON_CHECK');
          }
          continue;
        }
        const col = line.match(new RegExp(`^${IDENT}`));
        if (col) {
          columns.push(col[1]);
        }
      }

      tables.set(name, { name, file, columns, indexes, constraints });
    }
  }

  return { tables };
}

/** 移除 `-- ...` 行注释（不动字符串里的 `--`，够用即可） */
function stripLineComments(text) {
  return text
    .split(/\r?\n/)
    .map((l) => l.replace(/--.*$/, ''))
    .join('\n');
}

/** 从 open 位置（指向 '('）找到配对的 ')' 的下标，考虑嵌套与字符串 */
function matchParen(s, openIdx) {
  let depth = 0;
  let inStr = null;
  for (let i = openIdx; i < s.length; i++) {
    const c = s[i];
    if (inStr) {
      if (c === '\\') {
        i++;
      } else if (c === inStr) {
        inStr = null;
      }
      continue;
    }
    if (c === "'" || c === '"') {
      inStr = c;
      continue;
    }
    if (c === '(') {
      depth++;
    } else if (c === ')') {
      depth--;
      if (depth === 0) {
        return i;
      }
    }
  }
  return -1;
}

/** 按顶层逗号切分（不切括号内的逗号，如 DECIMAL(18,2)） */
function splitTopLevel(body) {
  const out = [];
  let depth = 0;
  let cur = '';
  let inStr = null;
  for (const c of body) {
    if (inStr) {
      cur += c;
      if (c === inStr) {
        inStr = null;
      }
      continue;
    }
    if (c === "'" || c === '"') {
      inStr = c;
      cur += c;
      continue;
    }
    if (c === '(') {
      depth++;
    }
    if (c === ')') {
      depth--;
    }
    if (c === ',' && depth === 0) {
      out.push(cur);
      cur = '';
    } else {
      cur += c;
    }
  }
  if (cur.trim()) {
    out.push(cur);
  }
  return out;
}
