import type { Envelope } from '@task/shared';

/**
 * 管理端接口封装。
 *
 * ⭐ 与 web-user 的区别：【必须带令牌】。这里统一注入 Authorization，
 *    ⛔ 不要让每个页面自己拼 header —— 漏一处就是一个静默的 401。
 *
 * ⚠️ 令牌存 sessionStorage 而不是 localStorage：
 *    管理台常在共用电脑上使用，关掉标签页即失效比"一直留着"更安全。
 *    ⛔ 真正的生产方案要用 HttpOnly Cookie + CSRF，
 *       这里如实说明是 Phase 1 的简化。
 */
const TOKEN_KEY = 'admin_token';

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY);
}
export function setToken(t: string): void {
  sessionStorage.setItem(TOKEN_KEY, t);
}
export function clearToken(): void {
  sessionStorage.removeItem(TOKEN_KEY);
}

export class ApiError extends Error {
  readonly code: number;
  readonly traceId?: string;
  constructor(code: number, message: string, traceId?: string) {
    super(message);
    this.code = code;
    this.traceId = traceId;
  }
}

export async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'Content-Type': 'application/json',
  };
  const t = getToken();
  if (t) headers.Authorization = 'Bearer ' + t;

  const res = await fetch(url, Object.assign({ headers }, init));
  const body = (await res.json().catch(() => null)) as Envelope<T> | null;
  if (!body) throw new ApiError(res.status, '服务端返回了非 JSON 响应（HTTP ' + res.status + '）');
  if (body.code === 20001) {
    clearToken();   // 令牌失效就地清掉，避免界面停在"已登录"的假象
    throw new ApiError(20001, '登录已失效，请重新登录', body.traceId);
  }
  if (!res.ok || body.success === false) {
    throw new ApiError(body.code, body.message || '请求失败', body.traceId);
  }
  return body.data;
}

export function login(phone: string, password: string) {
  return api<{ accessToken: string; refreshToken: string; userId: number; nickname: string }>(
    '/api/v1/auth/login',
    { method: 'POST', body: JSON.stringify({ phone, password }) },
  );
}

export function profile() {
  return api<{ userId: number; nickname: string; identity: string }>('/api/v1/account/profile');
}
