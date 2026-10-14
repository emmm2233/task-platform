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
 * @throws {Error} 括号不配平等解析失败 —— ⛔ 必须是硬错误，不能静默跳过
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
        // ⛔⛔ 这里绝【不能】写成 continue。
        //   静默跳过整张表的后果：该表从五道门禁里整体消失 ——
        //   门禁①不再校验它的字段、门禁③不再要求它有接口，
        //   于是"表里有而接口写错"这类缺陷全部变成绿灯。
        //   （反向验证实测过：只注入一个不存在的属性 → 门禁报错；
        //     同一个注入 + 把这行改成 continue 的括号不配平 → 0 违规。
        //     即"解析失败"会把"真缺陷"一起藏起来。）
        //   ⭐ 与门禁①的 nothing-checked 同一原则：解析失败是硬错误。
        throw new Error(
          `DDL 解析失败：${file} 中 CREATE TABLE \`${name}\` 的括号不配平（缺少或多余括号/引号）。` +
            ' 这会让整张表从所有一致性门禁里消失，必须先修 DDL。',
        );
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

    // ⭐ 前向迁移的新增列：ALTER TABLE `t` ADD COLUMN `c` ...
    //   没有这一步，第二次"加一列"的迁移就会让门禁①②对**合法的新列**误报
    //   （V7 已确立"只 ALTER、不改历史迁移"的先例）——
    //   而误报多的门禁一定会被关掉，或被人往 allowlist 里塞条目。
    applyAlterAddColumns(cleaned, tables);
  }

  if (tables.size === 0) {
    // ⛔ 一张表都没解析出来 = 这道门禁没在工作
    throw new Error('DDL 解析失败：没有解析出任何 CREATE TABLE，检查 db/migration 路径与文件扩展名');
  }

  return { tables };
}

/**
 * 收集 `ALTER TABLE ... ADD [COLUMN] ...` 新增的列，追加到对应表上。
 *
 * <p>⛔ 只认列：`ADD CONSTRAINT/KEY/INDEX/UNIQUE/PRIMARY/FOREIGN` 都不会被匹配
 * （它们后面紧跟的不是反引号标识符，或显式带关键字）。
 */
function applyAlterAddColumns(cleaned, tables) {
  const alterRe = /ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?`([^`]+)`/gi;
  let m;
  while ((m = alterRe.exec(cleaned)) !== null) {
    const table = tables.get(m[1]);
    if (!table) {
      continue; // 该表没有 CREATE TABLE（夹具/手工表），无从追加
    }
    const semi = cleaned.indexOf(';', m.index);
    const stmt = cleaned.slice(m.index, semi < 0 ? cleaned.length : semi);
    const addRe = /ADD\s+(?:COLUMN\s+)?`([^`]+)`/gi;
    let a;
    while ((a = addRe.exec(stmt)) !== null) {
      if (!table.columns.includes(a[1])) {
        table.columns.push(a[1]);
      }
    }
  }
}

/**
 * 移除 `-- ...` 行注释。
 *
 * <p>⛔ 必须**只在引号外**生效：DDL 里 `COMMENT 'rate--flag'` 这类字面量很常见，
 * 无脑 `replace(/--.*$/, '')` 会把引号切掉一半，导致括号/引号配对失败 ——
 * 而配对失败会顺着 parseDdl 的硬错误路径直接让门禁整体失败（或更早版本里静默丢表）。
 *
 * <p>同时保留字符串里的转义（`''` 与 `\'`），否则引号状态会误判。
 */
function stripLineComments(text) {
  let out = '';
  let inStr = null;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inStr) {
      out += c;
      if (c === '\\' && inStr === "'") {
        out += text[i + 1] ?? '';
        i++;
        continue;
      }
      if (c === inStr) {
        if (text[i + 1] === inStr) {
          out += text[i + 1];
          i++;
          continue;
        }
        inStr = null;
      }
      continue;
    }
    if (c === "'" || c === '"') {
      inStr = c;
      out += c;
      continue;
    }
    if (c === '-' && text[i + 1] === '-') {
      while (i < text.length && text[i] !== '\n') {
        i++;
      }
      out += '\n';
      continue;
    }
    out += c;
  }
  return out;
}

/** 从 open 位置（指向 '('）找到配对的 ')' 的下标，考虑嵌套与字符串 */
function matchParen(s, openIdx) {
  let depth = 0;
  let inStr = null;
  for (let i = openIdx; i < s.length; i++) {
    const c = s[i];
    if (inStr) {
      if (c === '\\' && inStr === "'") {
        i++;
      } else if (c === inStr) {
        if (s[i + 1] === inStr) {
          i++; // '' 转义
        } else {
          inStr = null;
        }
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
