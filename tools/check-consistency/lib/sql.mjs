/**
 * 从种子 SQL 里读取 INSERT 数据。
 *
 * 做一致性门禁必须真读种子数据（权限点、事件注册表都在里面），
 * ⛔ 不能靠正则扫全文 —— 一旦有注释或换行差异就会静默漏读。
 */

/**
 * 从一份 SQL 里抽出某张表**全部** INSERT 的行。
 *
 * ⚠️ 必须支持「同一张表被多条 INSERT 语句写入」——
 *    种子文件常因后续补充而追加第二条 INSERT。
 *    早期版本用非全局 match 只读第一条，于是 sys_config 只读到 58 个键（实际 89 条），
 *    让门禁 ② 的"配置键精确排除"静默失效。
 */
export function extractInsertRows(sql, table) {
  // 全局匹配：所有 INSERT INTO `table` (...) VALUES
  const re = new RegExp(`INSERT\\s+INTO\\s+\`${table}\`\\s*\\(([^)]*)\\)\\s*VALUES`, 'gi');
  const matches = [...sql.matchAll(re)];
  if (matches.length === 0) {
    return { columns: [], rows: [] };
  }

  let columns = [];
  const rows = [];
  for (const m of matches) {
    columns = m[1]
      .split(',')
      .map((c) => c.trim().replace(/`/g, ''))
      .filter(Boolean);

    const start = m.index + m[0].length;
    const end = findStatementEnd(sql, start);
    const body = sql.slice(start, end);

    for (const tuple of splitTuples(body)) {
      const vals = parseTuple(tuple);
      const obj = {};
      columns.forEach((c, i) => {
        obj[c] = vals[i];
      });
      rows.push(obj);
    }
  }
  return { columns, rows };
}

/** 找到语句结束的分号（跳过字符串与括号） */
function findStatementEnd(s, from) {
  let depth = 0;
  let inStr = null;
  for (let i = from; i < s.length; i++) {
    const c = s[i];
    if (inStr) {
      if (c === '\\') {
        i++;
      } else if (c === inStr) {
        inStr = null;
      }
      continue;
    }
    if (c === "'") {
      inStr = c;
      continue;
    }
    if (c === '(') {
      depth++;
    } else if (c === ')') {
      depth--;
    } else if (c === ';' && depth === 0) {
      return i;
    }
  }
  return s.length;
}

/** 切出所有顶层 (...) 元组 */
function splitTuples(body) {
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
    if (c === "'") {
      inStr = c;
      cur += c;
      continue;
    }
    if (c === '(') {
      depth++;
      if (depth === 1) {
        cur = '';
        continue;
      }
    }
    if (c === ')') {
      depth--;
      if (depth === 0) {
        out.push(cur);
        cur = '';
        continue;
      }
    }
    if (depth >= 1) {
      cur += c;
    }
  }
  return out;
}

/** 解析一行元组为值数组 */
function parseTuple(tuple) {
  const vals = [];
  let depth = 0;
  let cur = '';
  let inStr = null;
  for (const c of tuple) {
    if (inStr) {
      if (c === '\\') {
        cur += c;
        continue;
      }
      if (c === inStr) {
        inStr = null;
        continue;
      }
      cur += c;
      continue;
    }
    if (c === "'") {
      inStr = c;
      continue;
    }
    if (c === '(') {
      depth++;
      cur += c;
      continue;
    }
    if (c === ')') {
      depth--;
      cur += c;
      continue;
    }
    if (c === ',' && depth === 0) {
      vals.push(cur.trim());
      cur = '';
      continue;
    }
    cur += c;
  }
  vals.push(cur.trim());
  return vals;
}

/** 剥掉外层的单引号 */
export function unquote(v) {
  if (v == null) {
    return '';
  }
  const t = v.trim();
  if (t.startsWith("'") && t.endsWith("'") && t.length >= 2) {
    return t.slice(1, -1);
  }
  return t;
}

/** 从函数式值里取字符串数组，如 JSON_ARRAY('a','b') → ['a','b'] */
export function parseJsonArrayValue(v) {
  if (!v) {
    return [];
  }
  const m = v.match(/JSON_ARRAY\s*\(([\s\S]*)\)/i);
  const inner = m ? m[1] : v;
  return inner
    .split(',')
    .map((s) => unquote(s))
    .filter(Boolean);
}
