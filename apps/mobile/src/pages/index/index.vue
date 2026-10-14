<script setup lang="ts">
import { ref } from 'vue';
import { hall, type TaskSummary } from '../../api/client';

const items = ref<TaskSummary[]>([]);
const cursor = ref<string | null>(null);
const loading = ref(false);
const error = ref('');

/** 大厅匿名可浏览（后端已按方法限定放行 GET）——所以进页面直接拉，不拦登录。 */
async function load(reset: boolean) {
  if (loading.value) return;
  loading.value = true;
  error.value = '';
  try {
    const page = await hall({ cursor: reset ? null : cursor.value, pageSize: 10 });
    items.value = reset ? page.list : items.value.concat(page.list);
    cursor.value = page.cursor;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}
load(true);
</script>

<template>
  <view class="page">
    <view v-if="error" class="err">{{ error }}</view>
    <view v-for="t in items" :key="t.taskId" class="card">
      <view class="row1">
        <text class="title">{{ t.title }}</text>
        <text class="price">{{ t.budgetMax ?? t.price ?? '面议' }}</text>
      </view>
      <view class="row2">
        <text class="tag">{{ t.categoryName }}</text>
        <text class="tag">{{ t.statusName }}</text>
      </view>
    </view>
    <view v-if="!loading && items.length === 0 && !error" class="empty">没有符合条件的任务</view>
    <button v-if="cursor" :disabled="loading" @click="load(false)">
      {{ loading ? '加载中…' : '加载更多' }}
    </button>
  </view>
</template>

<style>
.page { padding: 24rpx; }
.card { background: #fff; border-radius: 12rpx; padding: 24rpx; margin-bottom: 16rpx; }
.row1 { display: flex; justify-content: space-between; }
.title { font-weight: 600; font-size: 30rpx; }
.price { color: #dc2626; font-size: 28rpx; }
.row2 { margin-top: 12rpx; }
.tag { font-size: 22rpx; color: #6b7280; background: #f3f4f6; padding: 2rpx 12rpx; border-radius: 6rpx; margin-right: 12rpx; }
.empty { text-align: center; color: #6b7280; padding: 60rpx 0; }
.err { color: #dc2626; background: #fef2f2; padding: 16rpx; border-radius: 8rpx; margin-bottom: 16rpx; }
</style>
