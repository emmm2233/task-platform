<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ApiError, clearToken, getToken, login, profile, setToken } from './api/adminClient';

/**
 * 管理台骨架的第一屏。
 *
 * ⭐ 它验证的是一条【端到端的关键路径】：登录 → 拿令牌 → 调受保护接口。
 *    管理端与用户端最大的差别就在这条路径上 —— 用户端大厅是匿名的，
 *    管理端【没有匿名态】，所以登录链路必须先跑通，后面的审核台才有意义。
 */
const phone = ref('');
const password = ref('');
const me = ref<{ userId: number; nickname: string; identity: string } | null>(null);
const error = ref('');
const busy = ref(false);

async function doLogin() {
  busy.value = true;
  error.value = '';
  try {
    const r = await login(phone.value, password.value);
    setToken(r.accessToken);
    me.value = await profile();
  } catch (e) {
    error.value = e instanceof ApiError
      ? e.message + (e.traceId ? '（traceId: ' + e.traceId + '）' : '')
      : String(e);
  } finally {
    busy.value = false;
  }
}

function logout() {
  clearToken();
  me.value = null;
}

onMounted(async () => {
  if (!getToken()) return;
  try { me.value = await profile(); } catch { /* 令牌失效已被 api() 清掉 */ }
});
</script>

<template>
  <header class="topbar">
    <strong>运营管理台</strong>
    <span class="hint">需登录 · 仅运营与管理员</span>
  </header>

  <main>
    <section v-if="!me" class="card">
      <h3>登录</h3>
      <label>手机号<input v-model="phone" placeholder="11 位手机号" /></label>
      <label>密码<input v-model="password" type="password" /></label>
      <p v-if="error" class="err">{{ error }}</p>
      <button :disabled="busy || !phone || !password" @click="doLogin">
        {{ busy ? '登录中…' : '登录' }}
      </button>
    </section>

    <section v-else class="card">
      <h3>已登录</h3>
      <p>昵称：{{ me.nickname }}</p>
      <p>身份：{{ me.identity }}</p>
      <p class="muted">
        后续模块（内容审核 / 实名审核 / 敏感词 / 工单）在此挂载。
      </p>
      <button @click="logout">退出</button>
    </section>
  </main>
</template>

<style>
body { margin: 0; font-family: system-ui, -apple-system, "Microsoft YaHei", sans-serif; background: #f5f6f8; }
.topbar { display: flex; align-items: baseline; gap: 12px; padding: 14px 20px; background: #1f2937; color: #fff; }
.hint { color: #9ca3af; font-size: 12px; }
main { max-width: 460px; margin: 40px auto; padding: 0 16px; }
.card { background: #fff; border: 1px solid #e5e7eb; border-radius: 10px; padding: 20px; }
label { display: block; margin: 10px 0; font-size: 13px; color: #374151; }
label input { display: block; width: 100%; margin-top: 4px; padding: 8px 10px; border: 1px solid #d1d5db; border-radius: 6px; box-sizing: border-box; }
button { margin-top: 12px; padding: 9px 20px; border: 0; border-radius: 6px; background: #2563eb; color: #fff; cursor: pointer; }
button:disabled { background: #9ca3af; cursor: not-allowed; }
.err { color: #dc2626; background: #fef2f2; padding: 8px 12px; border-radius: 6px; font-size: 13px; }
.muted { color: #6b7280; font-size: 13px; }
</style>
