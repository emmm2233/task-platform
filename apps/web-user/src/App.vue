<script setup lang="ts">
import HallView from './views/HallView.vue';
</script>

<template>
  <header class="topbar">
    <strong>任务大厅</strong>
    <span class="hint">未登录可浏览（匿名）</span>
  </header>
  <main>
    <HallView />
  </main>
</template>

<style>
body { margin: 0; font-family: system-ui, -apple-system, "Microsoft YaHei", sans-serif; background: #f5f6f8; }
.topbar { display: flex; align-items: baseline; gap: 12px; padding: 14px 20px; background: #fff; border-bottom: 1px solid #e5e7eb; }
.hint { color: #6b7280; font-size: 12px; }
main { max-width: 900px; margin: 0 auto; padding: 16px; }
</style>
