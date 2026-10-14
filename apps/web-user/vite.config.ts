import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

/**
 * ⚠️ 代理指向本地后端 8080。
 *
 * ⭐ 用代理而不是在前端写绝对地址：开发期同源，省掉一整套 CORS 配置，
 * 也就不会出现"本地能跑、上线跨域"这种只在部署时才暴露的问题。
 * ⛔ 生产环境由网关同源反代，前端代码不需要任何改动。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
    },
  },
});
