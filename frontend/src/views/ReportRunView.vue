<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  approveOutline,
  getRun,
  listMetrics,
  parseOutline,
  regenerateOutline,
  resumeRun,
  takePrimedRunDetail,
  type Outline,
  type RunDetail,
} from '@/api/report'
import { PHASES, fmtTime, statusMeta } from '@/utils/reportMeta'
import OutlineGate from '@/components/report/OutlineGate.vue'

const route = useRoute()
const detail = ref<RunDetail | null>(null)
const loadErr = ref('')
const actErr = ref('')
const busy = ref(false)
const reviseText = ref('')
const metricNames = ref<Record<string, string>>({})
let pollTimer: ReturnType<typeof setTimeout> | null = null

const run = computed(() => detail.value?.run ?? null)
const outline = computed<Outline | null>(() => (run.value ? parseOutline(run.value) : null))

/** RUNNING 但 run 行长时间未更新（服务端 resume 的 stale 判定为 2 分钟）→ 提示可续跑 */
const staleRunning = computed(() => {
  const r = run.value
  if (!r || r.status !== 'RUNNING' || !r.updatedAt) return false
  return Date.now() - Date.parse(r.updatedAt) > 180_000
})

const blockedInfo = computed(() => {
  const reason = run.value?.blockedReason ?? ''
  if (reason.startsWith('[POLICY]')) {
    return { kind: '业务性停止', text: reason.slice('[POLICY]'.length).trim() }
  }
  if (reason.startsWith('[EXCEPTION]')) {
    return { kind: '系统异常', text: reason.slice('[EXCEPTION]'.length).trim() }
  }
  return { kind: '', text: reason }
})

/** 流水线进度条：六步 + 两个人工卡点 */
const pipeCells = computed(() => {
  const d = detail.value
  if (!d) return []
  const r = d.run
  const lastStatus: Record<string, string> = {}
  for (const s of d.steps) lastStatus[s.phase] = s.status
  const cells: Array<{ label: string; cls: string; hitl?: boolean }> = PHASES.map((p) => {
    let cls = ''
    if (lastStatus[p.key] === 'OK') cls = 'done'
    if (r.phase === p.key) {
      if (r.status === 'RUNNING') cls = 'cur'
      if (r.status === 'BLOCKED') cls = 'blocked'
    }
    return { label: p.label, cls }
  })
  cells.splice(1, 0, {
    label: '🔒 卡点1 确认口径',
    cls: r.outlineApprovedAt ? 'done' : '',
    hitl: true,
  })
  cells.push({
    label: '✍️ 卡点2 审批签发',
    cls: r.status === 'PUBLISHED' ? 'done' : '',
    hitl: true,
  })
  return cells
})

const sortedSteps = computed(() =>
  [...(detail.value?.steps ?? [])].sort((a, b) => a.stepId - b.stepId),
)

function stopPoll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function schedulePoll() {
  stopPoll()
  if (run.value?.status === 'RUNNING') {
    pollTimer = setTimeout(async () => {
      try {
        detail.value = await getRun(route.params.id as string)
      } catch {
        /* 单次轮询失败静默重试，不清空已有数据 */
      }
      schedulePoll()
    }, 1500)
  }
}

async function load() {
  loadErr.value = ''
  actErr.value = ''
  const primed = takePrimedRunDetail(route.params.id as string)
  if (primed) {
    detail.value = primed
  } else {
    try {
      detail.value = await getRun(route.params.id as string)
    } catch (e) {
      loadErr.value = e instanceof Error ? e.message : '加载失败'
      return
    }
  }
  schedulePoll()
}

/** 动作统一封装：失败时展示业务错误并回读真实状态（并发竞态防护）。 */
async function doAction(fn: () => Promise<RunDetail>) {
  if (busy.value) return
  busy.value = true
  actErr.value = ''
  try {
    detail.value = await fn()
  } catch (e) {
    actErr.value = e instanceof Error ? e.message : '操作失败'
    try {
      detail.value = await getRun(route.params.id as string)
    } catch {
      /* 保留原数据 */
    }
  } finally {
    busy.value = false
    schedulePoll()
  }
}

const onApprove = (approver: string, o: Outline) =>
  doAction(() => approveOutline(run.value!.runId, approver, o))
const onRegenerate = (revised: string) =>
  doAction(() => regenerateOutline(run.value!.runId, revised))
const onResume = () => doAction(() => resumeRun(run.value!.runId))

onMounted(async () => {
  load()
  try {
    const list = await listMetrics()
    metricNames.value = Object.fromEntries(list.map((m) => [m.metricId, m.name]))
  } catch {
    /* 映射失败回退显示 metricId */
  }
})

watch(
  () => route.params.id,
  () => {
    if (route.name === 'report-run') {
      detail.value = null
      load()
    }
  },
)

onBeforeUnmount(stopPoll)
</script>

<template>
  <main class="run-page">
    <p v-if="loadErr" class="err card">{{ loadErr }}</p>

    <template v-else-if="run">
      <!-- 常驻：头部信息卡 + 流水线进度 -->
      <section class="card">
        <div class="head-row">
          <h1>报告 #{{ run.runId }}</h1>
          <span class="badge" :class="statusMeta(run.status).cls">{{
            statusMeta(run.status).label
          }}</span>
          <RouterLink to="/report" class="back">← 返回报告列表</RouterLink>
        </div>
        <div class="meta-grid">
          <div><span class="k">报告需求</span>{{ run.requestText }}</div>
          <div>
            <span class="k">报告模板</span>{{ run.templateId ?? '—'
            }}<template v-if="run.templateVersion">
              <span class="tag">v{{ run.templateVersion }}</span>（发起时固化）</template
            >
          </div>
          <div>
            <span class="k">报告期</span>{{ run.periodLabel ?? '—' }}
            <template v-if="run.periodStart">（{{ run.periodStart }} ~ {{ run.periodEnd }}）</template>
          </div>
          <div v-if="run.compareStart">
            <span class="k">对比期</span>{{ run.compareStart }} ~ {{ run.compareEnd }}
          </div>
          <div v-if="run.yoyStart">
            <span class="k">同比期</span>{{ run.yoyStart }} ~ {{ run.yoyEnd }}
          </div>
          <div v-if="run.outlineApprovedAt">
            <span class="k">口径确认</span>{{ run.outlineApprovedBy }} ·
            {{ fmtTime(run.outlineApprovedAt) }}
          </div>
          <div v-if="run.publishApprovedAt">
            <span class="k">签发</span>{{ run.publishApprovedBy }} ·
            {{ fmtTime(run.publishApprovedAt) }}
          </div>
        </div>
        <div class="pipe">
          <div
            v-for="(c, i) in pipeCells"
            :key="i"
            class="pstep"
            :class="[c.cls, { hitl: c.hitl }]"
          >
            {{ c.label }}
          </div>
        </div>
      </section>

      <p v-if="actErr" class="err card">{{ actErr }}</p>

      <!-- 条件区：按状态切换 -->
      <OutlineGate
        v-if="run.status === 'AWAITING_OUTLINE_APPROVAL' && outline"
        :outline="outline"
        :metric-names="metricNames"
        :busy="busy"
        @approve="onApprove"
        @regenerate="onRegenerate"
      />

      <section v-if="run.status === 'RUNNING'" class="card running">
        <h2>报告生成中…</h2>
        <p class="hint">
          当前步骤：{{ run.phase }}。页面每 1.5 秒自动刷新；章节撰写约需 30~90 秒，请稍候。
        </p>
        <div v-if="staleRunning" class="stale">
          任务疑似停摆（长时间无进展），可尝试断点续跑。
          <button class="btn-ghost" :disabled="busy" @click="onResume">断点续跑</button>
        </div>
      </section>

      <section v-if="run.status === 'BLOCKED'" class="card blocked">
        <h2>
          ⛔ 已停止，转人工处理
          <span v-if="blockedInfo.kind" class="tag tag-red">{{ blockedInfo.kind }}</span>
        </h2>
        <p class="reason">{{ blockedInfo.text }}</p>
        <p class="hint">
          系统按「失败关闭」原则停止，不做猜测性补全：外部条件修复后可断点续跑；如报告口径本身有误，请打回重新生成大纲。
        </p>
        <div class="actions">
          <button class="btn-primary" :disabled="busy" @click="onResume">
            断点续跑（从 {{ run.phase }} 重跑）
          </button>
          <input
            v-model="reviseText"
            class="revise-input"
            placeholder="打回意见（如：改为 2026 年 5 月）"
          />
          <button class="btn-danger" :disabled="busy" @click="onRegenerate(reviseText.trim())">
            打回重新生成大纲
          </button>
        </div>
      </section>

      <!-- 终稿区（阶段2 完整实现签发界面；当前提供预览） -->
      <section
        v-if="
          ['AWAITING_PUBLISH_APPROVAL', 'PUBLISHED', 'REJECTED'].includes(run.status) &&
          run.reportMd
        "
        class="card"
      >
        <h2>报告草稿预览</h2>
        <p class="hint">签发审批界面（含审计包与证据钻取）将在下一阶段提供。</p>
        <pre class="md-preview">{{ run.reportMd }}</pre>
      </section>

      <!-- 步骤留痕 -->
      <section v-if="sortedSteps.length" class="card">
        <details>
          <summary class="steps-summary">执行留痕（{{ sortedSteps.length }} 条步骤记录）</summary>
          <table class="steps">
            <thead>
              <tr>
                <th>步骤</th>
                <th>尝试</th>
                <th>状态</th>
                <th>开始</th>
                <th>结束</th>
                <th>异常</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in sortedSteps" :key="s.stepId">
                <td>{{ s.phase }}</td>
                <td>{{ s.attempt }}</td>
                <td>{{ s.status }}</td>
                <td>{{ fmtTime(s.startedAt) }}</td>
                <td>{{ fmtTime(s.finishedAt) }}</td>
                <td class="err-cell">{{ s.errorText ?? '' }}</td>
              </tr>
            </tbody>
          </table>
        </details>
      </section>
    </template>

    <p v-else-if="!loadErr" class="hint card">加载中…</p>
  </main>
</template>

<style scoped>
.run-page {
  flex: 1;
  overflow-y: auto;
  padding: 28px 24px 48px;
}

.run-page > * {
  max-width: 980px;
  margin-left: auto;
  margin-right: auto;
}

.card {
  margin-top: 16px;
  padding: 20px 24px;
  background: var(--tb-surface);
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius);
  box-shadow: var(--tb-shadow);
}

.card:first-child {
  margin-top: 0;
}

.card h2 {
  font-size: 16px;
  font-weight: 600;
  color: var(--tb-blue-900);
}

.head-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.head-row h1 {
  font-size: 20px;
  font-weight: 700;
  color: var(--tb-blue-900);
}

.back {
  margin-left: auto;
  font-size: 13px;
}

.badge {
  padding: 2px 10px;
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

.meta-grid {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 6px 24px;
  font-size: 13.5px;
}

.meta-grid .k {
  display: inline-block;
  min-width: 68px;
  color: var(--tb-text-3);
}

.tag {
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11.5px;
  color: var(--tb-blue-600);
  background: var(--tb-blue-50);
}

.tag-red {
  color: var(--tb-red);
  background: var(--tb-red-bg);
}

/* 流水线进度条 */
.pipe {
  margin-top: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.pstep {
  flex: 1;
  min-width: 96px;
  padding: 8px 6px;
  text-align: center;
  font-size: 12px;
  color: var(--tb-text-3);
  background: var(--tb-bg);
  border: 1px solid var(--tb-border);
  border-radius: 8px;
}

.pstep.hitl {
  border-style: dashed;
}

.pstep.done {
  color: var(--tb-green);
  background: var(--tb-green-bg);
  border-color: transparent;
}

.pstep.cur {
  color: var(--tb-blue-600);
  background: var(--tb-blue-50);
  border-color: var(--tb-blue-500);
  animation: breathe 1.6s infinite ease-in-out;
}

.pstep.blocked {
  color: var(--tb-red);
  background: var(--tb-red-bg);
  border-color: var(--tb-red);
}

@keyframes breathe {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.55;
  }
}

.hint {
  color: var(--tb-text-3);
  font-size: 13px;
  margin-top: 6px;
}

.err {
  color: var(--tb-red);
  font-size: 13.5px;
}

.running h2 {
  color: var(--tb-blue-600);
}

.stale {
  margin-top: 12px;
  padding: 10px 14px;
  background: var(--tb-amber-bg);
  border-radius: 8px;
  font-size: 13px;
  color: var(--tb-amber);
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.blocked h2 {
  color: var(--tb-red);
  display: flex;
  align-items: center;
  gap: 10px;
}

.reason {
  margin-top: 10px;
  padding: 10px 14px;
  background: var(--tb-red-bg);
  border-radius: 8px;
  font-size: 13.5px;
  color: var(--tb-red);
  word-break: break-all;
}

.actions {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.revise-input {
  flex: 1;
  min-width: 220px;
  padding: 6px 12px;
  border: 1px solid var(--tb-border);
  border-radius: 7px;
  font-family: var(--tb-font);
  font-size: 13px;
  outline: none;
}

.revise-input:focus {
  border-color: var(--tb-blue-500);
}

.btn-primary {
  padding: 7px 18px;
  border: none;
  border-radius: 8px;
  background: var(--tb-blue-600);
  color: #fff;
  font-size: 13.5px;
  font-weight: 500;
  cursor: pointer;
}

.btn-primary:hover:not(:disabled) {
  background: var(--tb-blue-700);
}

.btn-danger {
  padding: 7px 18px;
  border: 1px solid var(--tb-red);
  border-radius: 8px;
  background: var(--tb-surface);
  color: var(--tb-red);
  font-size: 13.5px;
  cursor: pointer;
}

.btn-ghost {
  padding: 5px 14px;
  border: 1px solid var(--tb-border);
  border-radius: 7px;
  background: var(--tb-surface);
  color: var(--tb-text-2);
  font-size: 13px;
  cursor: pointer;
}

.btn-primary:disabled,
.btn-danger:disabled,
.btn-ghost:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.md-preview {
  margin-top: 12px;
  padding: 14px 16px;
  background: var(--tb-bg);
  border-radius: 8px;
  font-family: var(--tb-font);
  font-size: 13.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.steps-summary {
  cursor: pointer;
  color: var(--tb-blue-600);
  font-size: 13.5px;
  user-select: none;
}

.steps {
  margin-top: 12px;
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}

.steps th,
.steps td {
  padding: 6px 10px;
  text-align: left;
  border-bottom: 1px solid var(--tb-border);
}

.steps th {
  color: var(--tb-text-3);
  font-weight: 600;
}

.err-cell {
  color: var(--tb-red);
  max-width: 320px;
  word-break: break-all;
}
</style>
