import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

/** 与 web-user 同一套取舍：代理 /api 到 8080，前端不写绝对地址（免 CORS）。 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5175,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } },
  },
});
