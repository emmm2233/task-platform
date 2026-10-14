import { readFileSync, existsSync } from 'node:fs';
import { readFiles } from './fs.mjs';

/**
 * 从文档里抽出对**数据库对象**的引用。
 *
 * ⚠️ 关键难点：设计文档里 `a.b` 形式的东西远不止「表.列」一种，至少还有：
 *   ① sys_config 的键   —— 如 `security.refresh_grace_seconds`、`task.bid_visible_mode`
 *                           （`task` 恰好也是表名，但 bid_visible_mode 不是它的列）
 *   ② 文件名            —— 如 `pom.xml`、`app.json`
 *   ③ 约束 / 索引名     —— 如 `ck_comm_rate`、`idx_geo`
 *   ④ 占位示例          —— 如 `xxx.yyy`
 *
 * 不区分这些，门禁会大量误报，然后团队就会把它关掉 —— 那还不如不做。
 * 所以本模块用**精确规则**而非启发式：
 *   - 配置键：与种子数据里真实存在的 sys_config.config_key 精确比对
 *   - 文件名：后缀白名单
 *   - 约束名：前缀白名单
 *   - 历史引述：跳过「缺陷审计台账」「变更记录」小节 —— 那里记录的是历史事实，
 *               不该被"当前状态"门禁约束
 */

const FILE_EXT = new Set([
  'json', 'xml', 'yml', 'yaml', 'md', 'java', 'sql', 'ps1', 'js', 'mjs', 'cjs',
  'ts', 'vue', 'html', 'css', 'sh', 'bat', 'properties', 'txt', 'csv', 'conf',
  'jar', 'war', 'zip', 'png', 'jpg', 'svg', 'lock', 'toml', 'ini',
]);

const CONSTRAINT_PREFIX = ['ck_', 'uk_', 'idx_', 'fk_', 'pk_', 'ix_'];

/** 这些小节记录的是历史事实（当初错在哪、改了什么），不参与"当前状态"校验 */
const HISTORICAL_HEADING = /(缺陷审计台账|文档变更记录|变更记录|CHANGELOG)/i;

/**
 * @param {string} docsDir 文档目录（递归扫描 *.md）
 * @param {{configKeys?: Set<string>, extraDocFiles?: string[]}} [opts]
 *        extraDocFiles：仓库根等处的单篇 markdown。
 *        ⚠️ 必须显式列出 —— 只扫 docs/ 会漏掉 README.md 这类根目录文档，
 *           门禁就会"看起来在工作"却漏检（本门禁第一版就栽在这上面）。
 * @returns {{refs: Array, skipped: Array}}
 */
export function parseDocRefs(docsDir, opts = {}) {
  const configKeys = opts.configKeys ?? new Set();
  const files = readFiles(docsDir, ['.md']);
  for (const f of opts.extraDocFiles ?? []) {
    if (existsSync(f)) {
      files.push({ file: f, text: readFileSync(f, 'utf8').replace(/^\uFEFF/, '') });
    }
  }
  const refs = [];
  const skipped = [];

  for (const { file, text } of files) {
    const lines = text.split(/\r?\n/);
    let inFence = false;
    /**
     * ⚠️ 必须是**标题栈**而不是"当前标题"：
     *    §6.7 缺陷审计台账 下有 #### 6.7.1 这样的子标题，
     *    只记最后一个标题会丢掉父级上下文，导致"历史引述"规则失效、误报一堆。
     */
    const headingStack = [];

    lines.forEach((line, i) => {
      if (/^\s*(```|~~~)/.test(line)) {
        inFence = !inFence;
        return;
      }
      if (inFence) {
        return;
      }
      const h = line.match(/^(#{1,6})\s+(.*)$/);
      if (h) {
        const level = h[1].length;
        headingStack.length = level - 1;
        headingStack[level - 1] = h[2].trim();
        return;
      }

      const breadcrumb = headingStack.filter(Boolean).join(' / ');
      const historical = HISTORICAL_HEADING.test(breadcrumb);

      for (const m of line.matchAll(/`([a-z][a-z0-9_]*)\.([a-z][a-z0-9_]*)`/g)) {
        const [, table, column] = m;
        const full = `${table}.${column}`;

        let reason = null;
        if (FILE_EXT.has(column)) {
          reason = '文件名（后缀命中白名单）';
        } else if (CONSTRAINT_PREFIX.some((p) => column.startsWith(p))) {
          reason = '约束/索引名（前缀命中白名单）';
        } else if (configKeys.has(full)) {
          reason = 'sys_config 键（与种子数据精确匹配）';
        } else if (historical) {
          reason = `历史引述（小节：${breadcrumb}）`;
        }

        const item = { file, line: i + 1, table, column, section: breadcrumb, text: line.trim() };
        if (reason) {
          skipped.push({ ...item, reason });
        } else {
          refs.push(item);
        }
      }
    });
  }

  return { refs, skipped };
}
