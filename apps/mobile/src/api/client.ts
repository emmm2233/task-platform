import { baseUrl } from '../platform/env';

/**
 * 请求封装。
 *
 * ⚠️ 小程序端【没有 fetch】，必须用 uni.request。
 * 这正是平台差异，所以通过 platform/env 暴露的 baseUrl + 这里的条件编译处理；
 * ⛔ 但条件编译本身只允许出现在 src/platform/，所以这里用运行时判断 uni 是否存在，
 *    而不是 #ifdef —— 保持"差异集中在 platform/"这条纪律。
 */
// ⭐ 类型来自 @task/shared —— 四端共用一份契约。
//    ⛔ 原先这里手写了一份 TaskSummary，那是【已记录的漂移风险点】：
//       后端改字段名，三端要到运行时才发现。
import type { Envelope, PageResult, TaskSummary } from '@task/shared';
export type { Envelope, PageResult, TaskSummary };

export function request<T>(url: string, method: 'GET' | 'POST' = 'GET', data?: unknown): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: baseUrl() + url,
      method,
      data: data as Record<string, unknown>,
      header: { Accept: 'application/json', 'Content-Type': 'application/json' },
      success: (res) => {
        const body = res.data as Envelope<T>;
        if (!body || typeof body.success === 'undefined') {
          reject(new Error('响应不是统一信封（HTTP ' + res.statusCode + '）'));
          return;
        }
        if (body.success === false) {
          reject(new Error(body.message + (body.traceId ? '（traceId: ' + body.traceId + '）' : '')));
          return;
        }
        resolve(body.data);
      },
      fail: (e) => reject(new Error(e.errMsg || '网络请求失败')),
    });
  });
}


export function hall(params: { keyword?: string; cursor?: string | null; pageSize?: number }) {
  const qs = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => k + '=' + encodeURIComponent(String(v)))
    .join('&');
  return request<PageResult<TaskSummary>>('/tasks' + (qs ? '?' + qs : ''));
}
