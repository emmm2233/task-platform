/**
 * ⭐ 平台差异【唯一】允许出现的地方。
 *
 * 纪律：`#ifdef` / `#ifndef` 只允许写在 src/platform/ 下。
 * ⛔ 不要散落到页面与组件里 —— 那样一套代码会被切成两份，
 *    "写一次、三端跑" 的收益就没了，退化成三份互相漂移的代码。
 *
 * 需要新平台差异时：在这里加一个函数或常量，页面调用它。
 */
// #ifdef MP-WEIXIN
export const PLATFORM = 'mp-weixin';
// #endif
// #ifdef H5
export const PLATFORM = 'h5';
// #endif
// #ifdef APP-PLUS
export const PLATFORM = 'app';
// #endif
// #ifndef MP-WEIXIN || H5 || APP-PLUS
export const PLATFORM = 'unknown';
// #endif

/** 各端的基地址。⛔ 不要在各页面里写死域名。 */
export function baseUrl(): string {
  // #ifdef H5
  return '/api/v1';                    // H5 走同源代理，免 CORS
  // #endif
  // #ifndef H5
  return 'http://127.0.0.1:8080/api/v1';   // 小程序/App 无同源概念，必须写全
  // #endif
}
