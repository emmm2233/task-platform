# apps/mobile（uni-app：微信小程序 + App）

⭐ **一套代码出三端**，这是选 uni-app 而不是各写一份的原因。

## 跑起来

    pnpm install                      # 在【仓库根】执行
    pnpm --filter mobile dev:mp-weixin    # 产物在 dist/dev/mp-weixin
    pnpm --filter mobile build:mp-weixin  # 产物在 dist/build/mp-weixin

然后用**微信开发者工具**打开 `dist/dev/mp-weixin`（或 build 目录）。
⚠️ `src/manifest.json` 里的 `mp-weixin.appid` 目前为空 ——
填上自己的测试号 AppID 才能真机预览。

## ⚠️ 版本线：@dcloudio 的 latest **不是** Vue3 线

⛔ **本机踩过**：按 `latest` 装会装到 **Vue 2 线**（`2.0.2-...`），
而 `vite-plugin-uni` 只有 3.x —— 两者不匹配，报
`Cannot find module .../uni-mp-weixin/lib/uni.plugin.js`。

**正确做法：全部 pin 到 `vue3` 标签指向的同一个版本**

    @dcloudio/*  3.0.0-alpha-5030120260930001

查看当前正确的版本：

    npm view @dcloudio/uni-app dist-tags
    # vue3: 3.0.0-alpha-...   ←⭐ 认这个
    # latest: 2.0.2-...       ← Vue2 线，不要用

⛔ `latest` 在这组包里不代表"最新可用"，而代表"默认那条线"。
升级时六个包必须**一起升到同一个版本**，否则又会踩同一条。

## 构建已核验

    pnpm --filter mobile build:mp-weixin

    编译器版本：5.31（vue3）
    正在编译中...
    DONE  Build complete.

产物（dist/build/mp-weixin）：

    app.js / app.json / App.wxml / app.wxss / project.config.json
    pages/index/index.wxml + index.js + index.json + index.wxss
    platform/env.js        ← 平台差异被编译成了独立模块（纪律生效）
    api/client.js
    common/vendor.js       67,986 B

用**微信开发者工具**打开 `dist/build/mp-weixin` 即可看到大厅页面。
⚠️ `src/manifest.json` 的 `mp-weixin.appid` 为空，真机预览要填自己的测试号 AppID。

## ⛔ 平台差异的纪律（最重要的一条）

**`#ifdef` / `#ifndef` 只允许写在 `src/platform/` 下。**

需要平台差异时：在 `src/platform/` 里加一个函数或常量，页面调用它。
⛔ 不要让条件编译散落到页面与组件里 —— 那样一套代码会被切成两份，
"写一次、三端跑"的收益就没了，**退化成三份互相漂移的代码**。

当前 `src/platform/env.ts` 收敛了两处差异：
平台标识（`PLATFORM`）与基地址（`baseUrl`，H5 走同源代理、小程序必须写全）。

## 与两端 Web 的复用

- 接口形状与 web-user 一致（统一信封 `{code,message,data,traceId,success}`）。
  ⚠️ 目前**手写了一份** `TaskSummary`，**尚未接到 `@task/shared`** ——
  这是已知的漂移风险点，如实记录，别当成已完成。
- ⚠️ 小程序没有 `fetch`，用 `uni.request`。封装在 `src/api/client.ts`。

## 大厅是匿名的

同 web-user：后端已【按方法限定为 GET】放行 `/api/v1/tasks` 与 `/api/v1/categories`，
所以进小程序直接看到大厅，不拦登录。抢单 / 报价 / 发单仍需登录。
