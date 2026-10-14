/**
 * 极薄的接口封装。
 *
 * ⭐ 刻意不引 axios：全站只有一个信封结构，fetch 足够；
 * 少一个依赖就少一处升级与体积负担。真需要拦截器时再加不迟。
 *
 * ⚠️ 后端的统一信封是 { code, message, data, traceId, timestamp, success }。
 * ⛔ 不要只看 HTTP 状态码就认为成功：业务失败也可能带 200
 * （例如非法游标返回 200 + success=false）。
 * 所以这里【同时】检查 HTTP 与信封里的 success。
 */
// ⭐ 类型来自 @task/shared —— 四端共用一份契约（由 api/openapi.yaml 自动生成）。
//    ⛔ 不要再在这里手写接口类型：后端改个字段名，前端要到运行时才发现。
import type { Envelope, PageResult, TaskSummary } from '@task/shared';
export type { Envelope, PageResult, TaskSummary };

export class ApiError extends Error {
  readonly code: number;
  readonly traceId?: string;
  constructor(code: number, message: string, traceId?: string) {
    super(message);
    this.code = code;
    this.traceId = traceId;
  }
}

export async function apiGet<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const qs = new URLSearchParams();
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null && v !== '') qs.append(k, String(v));
    }
  }
  const full = qs.toString() ? url + '?' + qs.toString() : url;
  const res = await fetch(full, { headers: { Accept: 'application/json' } });
  const body = (await res.json().catch(() => null)) as Envelope<T> | null;

  if (!body) {
    throw new ApiError(res.status, '服务端返回了非 JSON 响应（HTTP ' + res.status + '）');
  }
  if (!res.ok || body.success === false) {
    throw new ApiError(body.code, body.message || '请求失败', body.traceId);
  }
  return body.data;
}

export function hallQuery(p: {
  keyword?: string; categoryCode?: string; cursor?: string | null; pageSize?: number;
}): Promise<PageResult<TaskSummary>> {
  return apiGet<PageResult<TaskSummary>>('/api/v1/tasks', p as Record<string, unknown>);
}

export function categories(): Promise<Array<{ id: number; code: string; name: string }>> {
  return apiGet<Array<{ id: number; code: string; name: string }>>('/api/v1/categories', { level: 1 });
}
