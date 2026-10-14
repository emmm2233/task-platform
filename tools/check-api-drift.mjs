/**
 * 契约漂移检查：generated.ts 不能比 openapi.yaml 旧。
 *
 * ⭐ 为什么需要它：改了 openapi.yaml 却忘了 pnpm gen:api，
 *    前端类型就与真实接口不一致 —— 而这要到运行时才炸。
 *    靠人记得是不可靠的（本项目已被"注释不是机制"打过一次脸）。
 */
import { statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const REPO = fileURLToPath(new URL('..', import.meta.url));
const spec = join(REPO, 'api', 'openapi.yaml');
const gen  = join(REPO, 'packages', 'shared', 'src', 'generated.ts');

const s = statSync(spec).mtimeMs;
const g = statSync(gen).mtimeMs;
if (g < s) {
  console.error('❌ generated.ts 比 openapi.yaml 旧 —— 契约改过但没重新生成。');
  console.error('   跑：pnpm gen:api');
  process.exit(1);
}
console.log('✅ 生成物不比契约旧（spec=' + new Date(s).toISOString() + '）');
