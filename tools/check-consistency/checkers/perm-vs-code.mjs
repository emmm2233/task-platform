import { readFiles, readJsonIfExists } from '../lib/fs.mjs';
import { parsePreAuthorize, parsePermissions } from '../lib/java.mjs';

/**
 * 门禁 ④：权限点 ↔ 代码
 *
 * 代码里 {@code @PreAuthorize} 用到的每个权限点，必须在 {@code sys_permission} 里存在。
 *
 * ⭐ 这条抓的是「权限点写错一个字母」——
 *    后果是**该接口对所有人都 403**（或更糟：若框架配置不当则无人受限），
 *    而编译、启动、单测全都正常。
 */
export function check(ctx) {
  const sqlSources = readFiles(ctx.ddlDir, ['.sql']);
  const declared = parsePermissions(sqlSources);
  const { found, suspicious } = parsePreAuthorize(ctx.javaSrcDirs);
  const violations = [];

  for (const f of found) {
    if (!declared.has(f.code)) {
      violations.push({
        rule: 'perm-vs-code/undeclared-permission',
        file: `${f.file}:${f.line}`,
        message: `@PreAuthorize 引用了未登记的权限点 ${f.code}`,
        hint: '在 V4 种子里补 sys_permission，或改正拼写',
      });
    }
  }

  // ⛔ 用了表达式但抽不到权限点 → 门禁会静默失效，必须报警
  for (const s of suspicious) {
    violations.push({
      rule: 'perm-vs-code/unparseable-expression',
      file: `${s.file}:${s.line}`,
      message: `@PreAuthorize 里没有可识别的权限点字面量：${s.text}`,
      hint: '⛔ 门禁无法校验拼接/常量形式的权限点 —— 请改为字面量，否则这道门禁形同虚设',
    });
  }

  return violations;
}
