import { createSSRApp } from 'vue';
import App from './App.vue';

/**
 * ⚠️ uni-app 必须用 createSSRApp（不是 createApp）——
 * 小程序端没有 DOM，需要在服务端渲染式的生命周期里创建应用。
 */
export function createApp() {
  const app = createSSRApp(App);
  return { app };
}
