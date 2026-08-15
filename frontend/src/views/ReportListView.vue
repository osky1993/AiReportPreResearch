<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createRun, listRuns, primeRunDetail, type ReportRun } from '@/api/report'
import { fmtTime, statusMeta } from '@/utils/reportMeta'

const EXAMPLES = [
  { text: '生成 2026 年第 26 周的司库资金周报，和上周对比', note: '资金周报 · 第 26 周' },
  { text: '出一份 2026 年第 26 周的资金快报', note: '资金快报 · 第 26 周' },
  { text: '生成 2026 年 6 月的库款月报，和上月及去年同期对比', note: '明说环比+同比' },
  { text: '生成 2026 年 6 月的资金月报', note: '资金月报 · 2026 年 6 月' },
]

const router = useRouter()
const input = ref('')
const creating = ref(false)
const createErr = ref('')
const runs = ref<ReportRun[]>([])
const listErr = ref('')
const listLoading = ref(false)

async function loadRuns() {
  listLoading.value = true
  listErr.value = ''
  try {
    runs.value = await listRuns()
  } catch (e) {
    listErr.value = e instanceof Error ? e.message : '加载运行列表失败'
  } finally {
    listLoading.value = false
  }
}

async function submit(text?: string) {
  const requestText = (text ?? input.value).trim()
  if (!requestText || creating.value) return
  if (text) input.value = text
  creating.value = true
  createErr.value = ''
  try {
    const detail = await createRun(requestText)
    primeRunDetail(detail)
    router.push(`/report/runs/${detail.run.runId}`)
  } catch (e) {
    createErr.value = e instanceof Error ? e.message : '发起失败，请稍后重试'
  } finally {
    creating.value = false
  }
}

onMounted(loadRuns)
</script>

<template>
  <main class="report">
    <section class="head">
      <h1>智能报告</h1>
      <p>
        一句话发起报告：口径由您确认后锁死，取数与核数全程程序把关，数字一致率 100% 方可签发。
      </p>
    </section>

    <!-- 发起区 -->
    <section class="card">
      <h2>发起报告</h2>
      <textarea
        v-model="input"
        rows="2"
        placeholder="请描述报告需求，如：生成 2026 年第 26 周的司库资金周报"
        @keydown.enter.exact.prevent="submit()"
      ></textarea>
      <div class="chips">
        <button
          v-for="ex in EXAMPLES"
          :key="ex.text"
          class="chip"
          :disabled="creating"
          :title="ex.text"
          @click="submit(ex.text)"
        >
          {{ ex.note }}
        </button>
      </div>
      <div class="actions">
        <button class="btn-primary" :disabled="creating || !input.trim()" @click="submit()">
          {{ creating ? '大纲生成中，约 5~20 秒…' : '发起报告' }}
        </button>
        <span class="hint">发起后先生成大纲供您确认口径，确认前不会取数与成文。</span>
      </div>
      <p v-if="createErr" class="err">{{ createErr }}</p>
    </section>

    <!-- 历史运行列表 -->
    <section class="card">
      <div class="list-head">
        <h2>报告记录</h2>
        <button class="btn-ghost" :disabled="listLoading" @click="loadRuns">刷新</button>
      </div>
      <p v-if="listErr" class="err">{{ listErr }}</p>
      <p v-else-if="listLoading && runs.length === 0" class="hint">加载中…</p>
      <p v-else-if="runs.length === 0" class="hint">还没有报告记录，从上方发起第一份报告。</p>
      <div
        v-for="r in runs"
        :key="r.runId"
        class="run-item"
        @click="router.push(`/report/runs/${r.runId}`)"
      >
        <div class="run-title">
          <span class="run-id">#{{ r.runId }}</span>
          <span class="run-text">{{ r.requestText }}</span>
        </div>
        <div class="run-meta">
          <span class="badge" :class="statusMeta(r.status).cls">{{
            statusMeta(r.status).label
          }}</span>
          <span v-if="r.periodLabel">{{ r.periodLabel }}</span>
          <span>{{ fmtTime(r.createdAt) }}</span>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.report {
  flex: 1;
  overflow-y: auto;
  padding: 28px 24px 48px;
}

.report > * {
  max-width: 900px;
  margin-left: auto;
  margin-right: auto;
}

.head {
  text-align: center;
  padding: 20px 0 8px;
}

.head h1 {
  font-size: 26px;
  color: var(--tb-blue-900);
  font-weight: 700;
}

.head p {
  margin-top: 8px;
  color: var(--tb-text-2);
}

.card {
  margin-top: 20px;
  padding: 20px 24px;
  background: var(--tb-surface);
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius);
  box-shadow: var(--tb-shadow);
}

.card h2 {
  font-size: 16px;
  font-weight: 600;
  color: var(--tb-blue-900);
  margin-bottom: 12px;
}

textarea {
  width: 100%;
  resize: vertical;
  padding: 11px 16px;
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius);
  font-family: var(--tb-font);
  font-size: 14px;
  line-height: 1.5;
  color: var(--tb-text);
  outline: none;
}

textarea:focus {
  border-color: var(--tb-blue-500);
}

.chips {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.chip {
  padding: 5px 14px;
  border: 1px solid var(--tb-border);
  border-radius: 999px;
  background: var(--tb-surface);
  color: var(--tb-blue-700);
  font-size: 12.5px;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background 0.15s;
}

.chip:hover:not(:disabled) {
  border-color: var(--tb-blue-500);
  background: var(--tb-blue-50);
}

.chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.actions {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.btn-primary {
  padding: 9px 22px;
  border: none;
  border-radius: var(--tb-radius);
  background: var(--tb-blue-600);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-primary:hover:not(:disabled) {
  background: var(--tb-blue-700);
}

.btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.btn-ghost {
  padding: 4px 14px;
  border: 1px solid var(--tb-border);
  border-radius: 7px;
  background: var(--tb-surface);
  color: var(--tb-text-2);
  font-size: 13px;
  cursor: pointer;
}

.btn-ghost:hover:not(:disabled) {
  border-color: var(--tb-blue-500);
  color: var(--tb-blue-600);
}

.hint {
  color: var(--tb-text-3);
  font-size: 12.5px;
}

.err {
  margin-top: 10px;
  color: var(--tb-red);
  font-size: 13px;
}

.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.list-head h2 {
  margin-bottom: 0;
}

.run-item {
  margin-top: 10px;
  padding: 12px 14px;
  border: 1px solid var(--tb-border);
  border-radius: 8px;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background 0.15s;
}

.run-item:hover {
  border-color: var(--tb-blue-500);
  background: var(--tb-blue-50);
}

.run-title {
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.run-id {
  color: var(--tb-text-3);
  font-size: 12.5px;
  flex: none;
}

.run-text {
  font-weight: 500;
}

.run-meta {
  margin-top: 6px;
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: 12.5px;
  color: var(--tb-text-3);
}

.badge {
  padding: 1px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}

.badge-await {
  color: var(--tb-amber);
  background: var(--tb-amber-bg);
}

.badge-run {
  color: var(--tb-blue-600);
  background: var(--tb-blue-50);
}

.badge-block {
  color: var(--tb-red);
  background: var(--tb-red-bg);
}

.badge-pub {
  color: var(--tb-green);
  background: var(--tb-green-bg);
}

.badge-rej {
  color: var(--tb-text-3);
  background: var(--tb-bg);
}
</style>
