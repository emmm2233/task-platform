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

## ⚠️ pnpm 11 放行依赖构建脚本（曾经"配置写了但不生效"）

**背景**：pnpm 11 默认拦截依赖的 postinstall（供应链安全），
且把"有脚本被忽略"升级成 install 失败。esbuild 需要放平台二进制，必须放行。

### ✅ 正确做法（已修复，pnpm build 实测 exit 0）

在**仓库根**的 `pnpm-workspace.yaml` 里：

    allowBuilds:
      esbuild: true

⛔ **键名是 `allowBuilds`，不是 `onlyBuiltDependencies`。**
后者是旧键名/常见资料里的写法 —— 写了不报错、但**完全不生效**。

### 三个坑是叠在一起的，缺一都查不出来

1. **键名不对**。用 `onlyBuiltDependencies` 时 install 照旧报
   `ERR_PNPM_IGNORED_BUILDS`，而配置文件看起来"已经写对了"。
2. **workspace 根找错了**。pnpm 会自动在项目目录下创建
   `apps/web-user/pnpm-workspace.yaml`，于是它是一个**独立的 workspace 根** ——
   **仓库根的配置根本管不到它**（pnpm 只认【最近】的 workspace 根）。
   该文件已删除；现在根目录是唯一的 workspace 根。
3. **配置写在两处**。`apps/web-user/.npmrc` 里也写了一份放行设置，已删除 ——
   两处配置必然漂移。

### 怎么发现的

不靠猜：pnpm 自动生成的那个文件里**自己写着正确的键名**：

    allowBuilds:
      esbuild: set this to true or false

⇒ **工具报错时，先看它自己生成了什么、提示了什么**，
往往比按记忆或网上资料改要快。

## 端到端核验（可复跑）

只验证"能构建"是不够的 —— 构建通过但代理配错、或前端把绝对地址写死，
都要到真正联调时才暴露。这条链路已固化成一条命令：

    pwsh -File scripts/verify-e2e.ps1

它会起后端 jar + Vite dev server，然后**从【前端端口】打后端接口**。
本机实测结果（2026-10-15）：

    === 起后端 ===            后端 UP
    === 起前端 ===            前端 UP
    === ⭐ 从【前端端口】打后端接口（验证代理）===
      ✅ GET :5174/api/v1/tasks?pageSize=2        → HTTP 200
      ✅ GET :5174/api/v1/categories?level=1      → HTTP 200
      ✅ GET :5174/src/main.ts                    → HTTP 200
    ✅ 端到端通过：前端能经代理取到后端数据

代理返回的是真实数据（taskId 2444 / DEV 软件开发），不是 mock。
