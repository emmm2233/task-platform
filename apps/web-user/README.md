# web-user（用户端 Web）

Vite + Vue 3 + TypeScript。首屏是**任务大厅**。

## 跑起来

    pnpm install
    pnpm dev        # http://127.0.0.1:5174

后端要同时在跑（`java -jar platform-app/target/platform-app-1.0.0-SNAPSHOT.jar`）。
开发期由 Vite 代理 `/api` 到 8080，⛔ 前端代码里不写绝对地址 —— 免掉 CORS，
也不会出现"本地能跑、上线跨域"。

## 关于匿名浏览

大厅与分类是**匿名可访问**的（用户 2026-10-15 拍板），
后端在 `SecurityBaselineConfig` 里【按方法限定为 GET】放行。
⛔ 抢单 / 报价 / 发单等写操作仍需登录。

## 校验响应

后端统一信封是 `{ code, message, data, traceId, timestamp, success }`。
⛔ **不要只看 HTTP 状态码**：业务失败也可能带 200（例如非法游标）。
`src/api/client.ts` 同时检查 HTTP 与信封里的 `success`，并把 `traceId` 透出到界面 ——
用户报障时能直接给到日志，不用靠猜时间点。

## ⚠️ 本机已知环境坑（换机器必读；有一处【未解决】）

1. **pnpm 11 默认拦截依赖的构建脚本**（供应链安全），且把"有脚本被忽略"
   升级成 install 失败。esbuild 需要放平台二进制。

   ⛔ **未解决**：`pnpm build` 目前仍 exit 1（卡在 pnpm 自己的依赖状态前置检查）。
   试过但【无效】的做法，别重复踩：
   - `package.json` 里的 `pnpm.onlyBuiltDependencies` —— **pnpm 11 不读这个字段**；
   - `.npmrc` 里的 `strict-dep-builds=false` —— 实测仍失败。

   ✅ **目前可用且已验证的构建方式**（两条命令都跑过，产物一致）：

       pnpm rebuild esbuild                        # 放行 esbuild，放置平台二进制
       node node_modules/vite/bin/vite.js build    # 直接调 vite，绕过 pnpm 前置检查

   ⚠️ 换机器时若 `pnpm build` 报 `ERR_PNPM_IGNORED_BUILDS`，先跑一次 `pnpm rebuild esbuild`。
   彻底解决要在根目录建 pnpm workspace 并正确声明放行清单，**尚未做**。

## 已核验

    ✓ 16 modules transformed
    dist/index.html                  0.40 kB │ gzip:  0.28 kB
    dist/assets/index-*.css          1.78 kB │ gzip:  0.63 kB
    dist/assets/index-*.js          70.23 kB │ gzip: 28.15 kB
    ✓ built in 373ms
