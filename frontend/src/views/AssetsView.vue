<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  listCalibers,
  listMetricAssets,
  listTemplates,
  type CaliberAsset,
  type MetricSummary,
  type TemplateSummary,
} from '@/api/assets'

const TABS = [
  { kind: 'templates', label: '报告模板' },
  { kind: 'metrics', label: '指标口径' },
  { kind: 'calibers', label: '问数沉淀口径' },
] as const

type Kind = (typeof TABS)[number]['kind']

const route = useRoute()
const router = useRouter()
const kind = computed<Kind>(() => {
  const k = route.params.kind as string
  return (TABS.some((t) => t.kind === k) ? k : 'templates') as Kind
})

const templates = ref<TemplateSummary[]>([])
const metrics = ref<MetricSummary[]>([])
const calibers = ref<CaliberAsset[]>([])
const loading = ref(false)
const err = ref('')

const STATUS_LABEL: Record<string, { label: string; cls: string }> = {
  DRAFT: { label: '草稿', cls: 's-draft' },
  PUBLISHED: { label: '已发布', cls: 's-pub' },
  DEPRECATED: { label: '已下架', cls: 's-dep' },
  ACTIVE: { label: '生效中', cls: 's-pub' },
}

function st(status: string) {
  return STATUS_LABEL[status] ?? { label: status, cls: 's-dep' }
}

function fmtTime(s: string | null): string {
  return s ? s.replace('T', ' ').slice(0, 16) : '—'
}

async function load() {
  loading.value = true
  err.value = ''
  try {
    if (kind.value === 'templates') templates.value = await listTemplates()
    else if (kind.value === 'metrics') metrics.value = await listMetricAssets()
    else calibers.value = await listCalibers()
  } catch (e) {
    err.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(kind, load)
</script>

<template>
  <main class="assets">
    <section class="head">
      <h1>资产治理</h1>
      <p>报告模板、指标口径与问数沉淀口径的统一管理——多版本可追溯，发布与下架全程留痕。</p>
    </section>

    <nav class="tabs">
      <RouterLink
        v-for="t in TABS"
        :key="t.kind"
        :to="`/assets/${t.kind}`"
        class="tab"
        :class="{ active: kind === t.kind }"
      >
        {{ t.label }}
      </RouterLink>
    </nav>

    <section class="card">
      <p v-if="err" class="err">{{ err }}</p>
      <p v-else-if="loading" class="hint">加载中…</p>

      <!-- 模板 / 指标：同构多版本摘要表 -->
      <table v-else-if="kind === 'templates' || kind === 'metrics'">
        <thead>
          <tr>
            <th>名称</th>
            <th>资产 ID</th>
            <th>最新版本</th>
            <th>发布版本</th>
            <th>最新状态</th>
            <th>来源</th>
            <th>更新时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in kind === 'templates' ? templates : metrics"
            :key="kind === 'templates' ? (row as TemplateSummary).templateId : (row as MetricSummary).metricId"
            class="row"
            @click="
              router.push(
                `/assets/${kind}/${encodeURIComponent(kind === 'templates' ? (row as TemplateSummary).templateId : (row as MetricSummary).metricId)}`,
              )
            "
          >
            <td class="name">{{ row.name }}</td>
            <td class="mono">
              {{ kind === 'templates' ? (row as TemplateSummary).templateId : (row as MetricSummary).metricId }}
            </td>
            <td>v{{ row.latestVersion }}</td>
            <td>{{ row.publishedVersion ? `v${row.publishedVersion}` : '—' }}</td>
            <td>
              <span class="status" :class="st(row.latestStatus).cls">{{
                st(row.latestStatus).label
              }}</span>
            </td>
            <td>{{ row.source === 'SEED' ? '种子' : '人工' }}</td>
            <td>{{ fmtTime(row.updatedAt) }}</td>
          </tr>
        </tbody>
      </table>

      <!-- caliber：单行模型 -->
      <table v-else>
        <thead>
          <tr>
            <th>#</th>
            <th>业务问法</th>
            <th>状态</th>
            <th>沉淀人</th>
            <th>沉淀时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="c in calibers"
            :key="c.id"
            class="row"
            @click="router.push(`/assets/calibers/${c.id}`)"
          >
            <td class="mono">{{ c.id }}</td>
            <td class="name">{{ c.question }}</td>
            <td>
              <span class="status" :class="st(c.status).cls">{{ st(c.status).label }}</span>
            </td>
            <td>{{ c.createdBy ?? '—' }}</td>
            <td>{{ fmtTime(c.createdAt) }}</td>
          </tr>
        </tbody>
      </table>

      <p
        v-if="!loading && !err && kind === 'calibers' && calibers.length === 0"
        class="hint"
      >
        暂无沉淀口径——在智能问数中「核验采纳」AI 生成的结果即可沉淀到这里。
      </p>
    </section>
  </main>
</template>

<style scoped>
.assets {
  flex: 1;
  overflow-y: auto;
  padding: 28px 24px 48px;
}

.assets > * {
  max-width: 1000px;
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

.tabs {
  margin-top: 20px;
  display: flex;
  gap: 6px;
  border-bottom: 2px solid var(--tb-border);
}

.tab {
  padding: 8px 18px;
  font-size: 14px;
  color: var(--tb-text-2);
  border-radius: 8px 8px 0 0;
  margin-bottom: -2px;
  border-bottom: 2px solid transparent;
}

.tab:hover {
  color: var(--tb-blue-600);
  background: var(--tb-blue-50);
}

.tab.active {
  color: var(--tb-blue-700);
  font-weight: 600;
  border-bottom-color: var(--tb-blue-600);
}

.card {
  margin-top: 16px;
  padding: 8px 0;
  background: var(--tb-surface);
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius);
  box-shadow: var(--tb-shadow);
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13.5px;
}

th,
td {
  padding: 10px 16px;
  text-align: left;
  border-bottom: 1px solid var(--tb-border);
}

th {
  color: var(--tb-text-3);
  font-weight: 600;
  white-space: nowrap;
}

tbody tr:last-child td {
  border-bottom: none;
}

.row {
  cursor: pointer;
}

.row:hover {
  background: var(--tb-blue-50);
}

.name {
  font-weight: 500;
}

.mono {
  font-family: var(--tb-mono);
  font-size: 12px;
  color: var(--tb-text-2);
}

.status {
  padding: 1px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.s-pub {
  color: var(--tb-green);
  background: var(--tb-green-bg);
}

.s-draft {
  color: var(--tb-amber);
  background: var(--tb-amber-bg);
}

.s-dep {
  color: var(--tb-text-3);
  background: var(--tb-bg);
}

.hint {
  padding: 14px 16px;
  color: var(--tb-text-3);
  font-size: 13px;
}

.err {
  padding: 14px 16px;
  color: var(--tb-red);
  font-size: 13.5px;
}
</style>
