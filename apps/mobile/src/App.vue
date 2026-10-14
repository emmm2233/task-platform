<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app';

onLaunch(() => {
  // 小程序冷启动会走到这里；真正需要登录态的逻辑放在页面里按需判断，
  // ⛔ 不要在这里强制跳登录 —— 大厅是匿名可浏览的。
  console.log('app launched');
});
</script>

<template>
  <slot />
</template>
