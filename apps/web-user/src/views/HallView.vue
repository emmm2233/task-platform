<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ApiError, hallQuery, categories, type TaskSummary } from '../api/client';

const items = ref<TaskSummary[]>([]);
const cursor = ref<string | null>(null);
const loading = ref(false);
const error = ref<string>('');
const keyword = ref('');
const cats = ref<Array<{ id: number; code: string; name: string }>>([]);
const activeCat = ref<string>('');

/**
 * 游标分页：翻页靠 cursor，⛔ 不用 offset。
 * 大厅按发布时间倒序而任务随时在增删，用 offset 会让"翻页时有人发了新任务"
 * 变成重复显示。后端已按 (created_at desc, id desc) 保证不重不漏。
 */
async function load(reset: boolean) {
  if (loading.value) return;
  loading.value = true;
  error.value = '';
  try {
    const page = await hallQuery({
      keyword: keyword.value || undefined,
      categoryCode: activeCat.value || undefined,
      cursor: reset ? null : cursor.value,
      pageSize: 10,
    });
    items.value = reset ? page.list : items.value.concat(page.list);
    cursor.value = page.cursor;
  } catch (e) {
    // ⭐ 把 traceId 一起显示出来：用户报障时能直接给到日志，不用靠猜时间点
    error.value = e instanceof ApiError
      ? e.message + (e.traceId ? '（traceId: ' + e.traceId + '）' : '')
      : String(e);
  } finally {
    loading.value = false;
  }
}

function pickCat(code: string) {
  activeCat.value = activeCat.value === code ? '' : code;
  load(true);
}

onMounted(async () => {
  load(true);
  try { cats.value = await categories(); } catch { /* 分类拉不到不影响大厅 */ }
});
</script>

<template>
  <div class="bar">
    <input v-model="keyword" placeholder="搜索任务标题" @keyup.enter="load(true)" />
    <button @click="load(true)">搜索</button>
  </div>

  <div class="cats">
    <span
      v-for="c in cats" :key="c.id"
      class="cat" :class="{ on: activeCat === c.code }"
      @click="pickCat(c.code)"
    >{{ c.name }}</span>
  </div>

  <p v-if="error" class="err">{{ error }}</p>
  <p v-if="!loading && items.length === 0 && !error" class="empty">没有符合条件的任务</p>

  <ul class="list">
    <li v-for="t in items" :key="t.taskId" class="card">
      <div class="row1">
        <span class="title">{{ t.title }}</span>
        <span class="price">{{ t.budgetMax ?? t.price ?? '面议' }}</span>
      </div>
      <div class="row2">
        <span class="tag">{{ t.categoryName }}</span>
        <span class="tag">{{ t.statusName }}</span>
        <span class="meta">{{ t.publisherNickname }}</span>
      </div>
    </li>
  </ul>

  <div class="more">
    <button v-if="cursor" :disabled="loading" @click="load(false)">
      {{ loading ? '加载中…' : '加载更多' }}
    </button>
    <span v-else-if="items.length" class="end">已到底部</span>
  </div>
</template>

<style scoped>
.bar { display: flex; gap: 8px; margin-bottom: 10px; }
.bar input { flex: 1; padding: 8px 10px; border: 1px solid #d1d5db; border-radius: 6px; }
.bar button { padding: 8px 16px; border: 0; border-radius: 6px; background: #2563eb; color: #fff; cursor: pointer; }
.cats { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 12px; }
.cat { padding: 4px 10px; background: #fff; border: 1px solid #e5e7eb; border-radius: 999px; font-size: 13px; cursor: pointer; }
.cat.on { background: #2563eb; color: #fff; border-color: #2563eb; }
.list { list-style: none; padding: 0; margin: 0; }
.card { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px 14px; margin-bottom: 8px; }
.row1 { display: flex; justify-content: space-between; gap: 12px; }
.title { font-weight: 600; }
.price { color: #dc2626; white-space: nowrap; }
.row2 { margin-top: 6px; display: flex; gap: 8px; align-items: center; font-size: 12px; color: #6b7280; }
.tag { background: #f3f4f6; padding: 1px 8px; border-radius: 4px; }
.more { text-align: center; padding: 12px 0; }
.more button { padding: 8px 20px; border: 1px solid #d1d5db; background: #fff; border-radius: 6px; cursor: pointer; }
.err { color: #dc2626; background: #fef2f2; padding: 8px 12px; border-radius: 6px; }
.empty { color: #6b7280; text-align: center; padding: 24px 0; }
.end { color: #9ca3af; font-size: 12px; }
</style>
