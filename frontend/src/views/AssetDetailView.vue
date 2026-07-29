<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  AssetApiError,
  deprecateCaliber,
  deprecateMetric,
  deprecateTemplate,
  getCaliber,
  getMetric,
  getMetricReferences,
  getTemplate,
  getTemplateVersion,
  publishMetric,
  publishTemplate,
  type CaliberAsset,
  type MetricDetail,
  type TemplateBody,
  type TemplateDetail,
  type ValidationDetail,
  type VersionInfo,
} from '@/api/assets'

const route = useRoute()
const kind = computed(() => route.params.kind as 'templates' | 'metrics' | 'calibers')
const id = computed(() => route.params.id as string)

const tpl = ref<TemplateDetail | null>(null)
const tplBody = ref<TemplateBody | null>(null)
const tplBodyVersion = ref<number | null>(null)
const metric = ref<MetricDetail | null>(null)
const metricShown = ref<'published' | 'latest'>('published')
const caliber = ref<CaliberAsset | null>(null)
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

const CMP_LABELS: Record<string, string> = {
  month_over_month: '环比（较上月）',
  year_over_year: '同比（较上年同期）',
}

function cmpLabel(c: string): string {
  return CMP_LABELS[c] ?? c
}

const metricBody = computed(() => {
  if (!metric.value) return null
  return metricShown.value === 'published'
    ? (metric.value.published ?? metric.value.latest)
    : (metric.value.latest ?? metric.value.published)
})

const caliberMqlPretty = computed(() => {
  if (!caliber.value) return ''
  try {
    return JSON.stringify(JSON.parse(caliber.value.mqlJson), null, 2)
  } catch {
    return caliber.value.mqlJson
  }
})

// ---- 治理操作（发布/下架，行内确认条） ----

interface PendingAction {
  action: 'publish' | 'deprecate'
  version: number
  text: string
  warning: string
}

const pending = ref<PendingAction | null>(null)
const actBusy = ref(false)
const actErr = ref('')
const actErrDetails = ref<ValidationDetail[]>([])
const actOk = ref('')
const operator = ref('')

function clearActionState() {
  pending.value = null
  actErr.value = ''
  actErrDetails.value = []
  actOk.value = ''
}

async function askAction(action: 'publish' | 'deprecate', v: VersionInfo) {
  actErr.value = ''
  actErrDetails.value = []
  actOk.value = ''
  let warning = ''
  if (action === 'deprecate' && kind.value === 'metrics' && v.status === 'PUBLISHED') {
    try {
      const refs = await getMetricReferences(id.value)
      if (refs.referencedBy.length) {
        warning = `⚠️ 该指标正被已发布模板引用：${refs.referencedBy.join('、')}。若下架后该指标无其他发布版本，服务端自检将拒绝此操作并回滚。`
      }
    } catch {
      /* 引用检查失败不阻断，交由服务端把关 */
    }
  }
  const text =
    action === 'publish'
      ? `确认发布 v${v.version}？发布后旧的发布版本将自动下架，资产热加载立即生效。`
      : `确认下架 v${v.version}？下架后该版本不再参与报告生成（历史 run 已固化版本不受影响）。`
  pending.value = { action, version: v.version, text, warning }
}

async function runAction() {
  if (!pending.value || actBusy.value) return
  const { action, version } = pending.value
  actBusy.value = true
  try {
    if (kind.value === 'templates') {
      if (action === 'publish') await publishTemplate(id.value, version)
      else await deprecateTemplate(id.value, version)
    } else {
      if (action === 'publish') await publishMetric(id.value, version)
      else await deprecateMetric(id.value, version)
    }
    pending.value = null
    actOk.value = `v${version} ${action === 'publish' ? '已发布' : '已下架'}`
    await load()
    actOk.value = `v${version} ${action === 'publish' ? '已发布' : '已下架'}`
  } catch (e) {
    actErr.value = e instanceof Error ? e.message : '操作失败'
    actErrDetails.value = e instanceof AssetApiError ? e.details : []
  } finally {
    actBusy.value = false
  }
}

const caliberPending = ref(false)

async function runCaliberDeprecate() {
  if (!caliber.value || actBusy.value) return
  actBusy.value = true
  actErr.value = ''
  try {
    await deprecateCaliber(caliber.value.id, operator.value.trim() || '业务用户')
    caliberPending.value = false
    await load()
    actOk.value = '该口径已下架，智能问数不再召回'
  } catch (e) {
    actErr.value = e instanceof Error ? e.message : '操作失败'
  } finally {
    actBusy.value = false
  }
}

async function showTemplateVersion(v: VersionInfo) {
  if (!tpl.value) return
  try {
    const body = await getTemplateVersion(tpl.value.templateId, v.version)
    tplBody.value = body.template
    tplBodyVersion.value = v.version
  } catch (e) {
    err.value = e instanceof Error ? e.message : '加载版本失败'
  }
}

async function load() {
  loading.value = true
  err.value = ''
  pending.value = null
  caliberPending.value = false
  tpl.value = null
  metric.value = null
  caliber.value = null
  tplBody.value = null
  try {
    if (kind.value === 'templates') {
      tpl.value = await getTemplate(id.value)
      tplBody.value = tpl.value.published ?? tpl.value.latest
      const pubV = tpl.value.versions.find((v) => v.status === 'PUBLISHED')
      tplBodyVersion.value = pubV?.version ?? (tpl.value.versions.at(-1)?.version ?? null)
    } else if (kind.value === 'metrics') {
      metric.value = await getMetric(id.value)
      metricShown.value = metric.value.published ? 'published' : 'latest'
    } else {
      caliber.value = await getCaliber(id.value)
    }
  } catch (e) {
    err.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch([kind, id], load)
</script>

<template>
  <main class="detail">
    <p class="crumbs">
      <RouterLink :to="`/assets/${kind}`">← 返回资产列表</RouterLink>
    </p>
    <p v-if="err" class="err card">{{ err }}</p>
    <p v-else-if="loading" class="hint card">加载中…</p>

    <!-- ============ 模板详情 ============ -->
    <template v-else-if="kind === 'templates' && tpl">
      <section class="card">
        <h1>
          {{ tplBody?.name ?? tpl.templateId }}
          <span class="mono-id">{{ tpl.templateId }}</span>
        </h1>
        <h3>版本历史（行不可变，所有修改产生新版本）</h3>
        <table>
          <thead>
            <tr>
              <th>版本</th>
              <th>名称</th>
              <th>状态</th>
              <th>来源</th>
              <th>创建人</th>
              <th>时间</th>
              <th>备注</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="v in tpl.versions" :key="v.version">
              <td>v{{ v.version }}</td>
              <td>{{ v.name }}</td>
              <td>
                <span class="status" :class="st(v.status).cls">{{ st(v.status).label }}</span>
              </td>
              <td>{{ v.source === 'SEED' ? '种子' : '人工' }}</td>
              <td>{{ v.createdBy ?? '—' }}</td>
              <td>{{ fmtTime(v.createdAt) }}</td>
              <td class="remark">{{ v.remark ?? '' }}</td>
              <td class="ops">
                <button class="btn-ghost" @click="showTemplateVersion(v)">查看定义</button>
                <button
                  v-if="v.status === 'DRAFT'"
                  class="btn-ghost op-pub"
                  :disabled="actBusy"
                  @click="askAction('publish', v)"
                >
                  发布
                </button>
                <button
                  v-if="v.status !== 'DEPRECATED'"
                  class="btn-ghost op-dep"
                  :disabled="actBusy"
                  @click="askAction('deprecate', v)"
                >
                  下架
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="pending" class="confirm-bar">
          <p>{{ pending.text }}</p>
          <p v-if="pending.warning" class="warn">{{ pending.warning }}</p>
          <div class="confirm-actions">
            <button class="btn-primary" :disabled="actBusy" @click="runAction">
              {{ actBusy ? '处理中…' : '确认执行' }}
            </button>
            <button class="btn-ghost" :disabled="actBusy" @click="clearActionState">取消</button>
          </div>
        </div>
        <p v-if="actOk" class="ok">✓ {{ actOk }}</p>
        <p v-if="actErr" class="err">{{ actErr }}</p>
        <ul v-if="actErrDetails.length" class="err-details">
          <li v-for="(d, i) in actErrDetails" :key="i">
            <code>{{ d.location }}</code
            >：{{ d.message }}
          </li>
        </ul>
      </section>

      <section v-if="tplBody" class="card">
        <h3>
          模板定义
          <span v-if="tplBodyVersion" class="mono-id">v{{ tplBodyVersion }}</span>
        </h3>
        <div class="kv">
          <div v-if="tplBody.keywords?.length">
            <span class="k">匹配关键词</span>{{ tplBody.keywords.join('、') }}
          </div>
          <div v-if="tplBody.periodTypes?.length">
            <span class="k">支持周期</span>{{ tplBody.periodTypes.join('、') }}
          </div>
        </div>
        <div v-for="c in tplBody.chapters ?? []" :key="c.chapterId" class="chapter">
          <div class="chapter-head">
            <h4>{{ c.title }}</h4>
            <span v-for="cmp in c.comparisons ?? []" :key="cmp" class="tag">{{
              cmpLabel(cmp)
            }}</span>
            <span v-if="c.comparison && !c.comparisons?.length" class="tag">{{
              cmpLabel(String(c.comparison))
            }}</span>
          </div>
          <p v-if="c.guidance" class="guidance">{{ c.guidance }}</p>
          <p v-if="c.stylePrompt" class="style-note">✍️ 文风：{{ c.stylePrompt }}</p>
          <p v-if="c.metrics?.length" class="metric-ids">
            指标：<code v-for="m in c.metrics" :key="m">{{ m }}</code>
          </p>
        </div>
        <details class="raw">
          <summary>查看定义原文（JSON）</summary>
          <pre>{{ JSON.stringify(tplBody, null, 2) }}</pre>
        </details>
      </section>
    </template>

    <!-- ============ 指标详情 ============ -->
    <template v-else-if="kind === 'metrics' && metric">
      <section class="card">
        <h1>
          {{ metricBody?.name ?? metric.metricId }}
          <span class="mono-id">{{ metric.metricId }}</span>
        </h1>
        <h3>版本历史（行不可变，所有修改产生新版本）</h3>
        <table>
          <thead>
            <tr>
              <th>版本</th>
              <th>名称</th>
              <th>状态</th>
              <th>来源</th>
              <th>创建人</th>
              <th>时间</th>
              <th>备注</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="v in metric.versions" :key="v.version">
              <td>v{{ v.version }}</td>
              <td>{{ v.name }}</td>
              <td>
                <span class="status" :class="st(v.status).cls">{{ st(v.status).label }}</span>
              </td>
              <td>{{ v.source === 'SEED' ? '种子' : '人工' }}</td>
              <td>{{ v.createdBy ?? '—' }}</td>
              <td>{{ fmtTime(v.createdAt) }}</td>
              <td class="remark">{{ v.remark ?? '' }}</td>
              <td class="ops">
                <button
                  v-if="v.status === 'DRAFT'"
                  class="btn-ghost op-pub"
                  :disabled="actBusy"
                  @click="askAction('publish', v)"
                >
                  发布
                </button>
                <button
                  v-if="v.status !== 'DEPRECATED'"
                  class="btn-ghost op-dep"
                  :disabled="actBusy"
                  @click="askAction('deprecate', v)"
                >
                  下架
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="pending" class="confirm-bar">
          <p>{{ pending.text }}</p>
          <p v-if="pending.warning" class="warn">{{ pending.warning }}</p>
          <div class="confirm-actions">
            <button class="btn-primary" :disabled="actBusy" @click="runAction">
              {{ actBusy ? '处理中…' : '确认执行' }}
            </button>
            <button class="btn-ghost" :disabled="actBusy" @click="clearActionState">取消</button>
          </div>
        </div>
        <p v-if="actOk" class="ok">✓ {{ actOk }}</p>
        <p v-if="actErr" class="err">{{ actErr }}</p>
        <ul v-if="actErrDetails.length" class="err-details">
          <li v-for="(d, i) in actErrDetails" :key="i">
            <code>{{ d.location }}</code
            >：{{ d.message }}
          </li>
        </ul>
      </section>

      <section v-if="metricBody" class="card">
        <h3>
          口径定义
          <span class="switch">
            <button
              class="btn-ghost"
              :class="{ on: metricShown === 'published' }"
              :disabled="!metric.published"
              @click="metricShown = 'published'"
            >
              发布版
            </button>
            <button
              class="btn-ghost"
              :class="{ on: metricShown === 'latest' }"
              :disabled="!metric.latest"
              @click="metricShown = 'latest'"
            >
              最新版
            </button>
          </span>
        </h3>
        <div class="kv">
          <div><span class="k">指标名称</span>{{ metricBody.name }}</div>
          <div v-if="metricBody.unit"><span class="k">单位</span>{{ metricBody.unit }}</div>
          <div v-if="metricBody.valueColumn">
            <span class="k">取值列</span><code>{{ metricBody.valueColumn }}</code>
          </div>
          <div><span class="k">时点约束</span>{{ metricBody.timeBound ? '是' : '否' }}</div>
          <div><span class="k">可对比</span>{{ metricBody.comparable ? '是' : '否' }}</div>
          <div v-if="metricBody.nullPolicy">
            <span class="k">空值策略</span>{{ metricBody.nullPolicy }}
          </div>
          <div v-if="metricBody.qualityChecks?.length">
            <span class="k">质量断言</span>{{ metricBody.qualityChecks.join('、') }}
          </div>
        </div>
        <details class="raw" open>
          <summary>参数化取数模板（MQL，占位符仅 period_start / period_end）</summary>
          <pre>{{ JSON.stringify(metricBody.mqlTemplate ?? {}, null, 2) }}</pre>
        </details>
        <details class="raw">
          <summary>查看定义原文（JSON）</summary>
          <pre>{{ JSON.stringify(metricBody, null, 2) }}</pre>
        </details>
      </section>
    </template>

    <!-- ============ caliber 详情 ============ -->
    <template v-else-if="kind === 'calibers' && caliber">
      <section class="card">
        <h1>
          口径 #{{ caliber.id }}
          <span class="status" :class="st(caliber.status).cls">{{
            st(caliber.status).label
          }}</span>
        </h1>
        <div class="kv">
          <div><span class="k">业务问法</span>{{ caliber.question }}</div>
          <div>
            <span class="k">口径说明</span
            >{{ caliber.description ?? '—（沉淀于该能力上线前，可在问数页按需生成）' }}
          </div>
          <div><span class="k">沉淀人</span>{{ caliber.createdBy ?? '—' }}</div>
          <div><span class="k">沉淀时间</span>{{ fmtTime(caliber.createdAt) }}</div>
        </div>
        <p class="hint">
          生效中的口径会被智能问数召回复用（命中即确定性取数，不调大模型）。
        </p>
        <details class="raw" open>
          <summary>已核验的结构化查询（MQL）</summary>
          <pre>{{ caliberMqlPretty }}</pre>
        </details>

        <template v-if="caliber.status === 'ACTIVE'">
          <div v-if="!caliberPending" class="confirm-actions" style="margin-top: 14px">
            <RouterLink :to="`/assets/metrics/new?caliber=${caliber.id}`" class="btn-ghost">
              ⬆️ 升格为指标（直入参数化）
            </RouterLink>
            <button class="btn-ghost op-dep" :disabled="actBusy" @click="caliberPending = true">
              下架该口径
            </button>
          </div>
          <div v-else class="confirm-bar">
            <p>确认下架口径 #{{ caliber.id }}？下架后智能问数不再召回复用此口径（同类问题将回到 AI 生成路径）。</p>
            <div class="confirm-actions">
              <input v-model="operator" class="op-input" placeholder="操作人（默认：业务用户）" />
              <button class="btn-primary" :disabled="actBusy" @click="runCaliberDeprecate">
                {{ actBusy ? '处理中…' : '确认下架' }}
              </button>
              <button class="btn-ghost" :disabled="actBusy" @click="caliberPending = false">
                取消
              </button>
            </div>
          </div>
        </template>
        <p v-if="actOk" class="ok">✓ {{ actOk }}</p>
        <p v-if="actErr" class="err">{{ actErr }}</p>
      </section>
    </template>
  </main>
</template>

<style scoped>
.detail {
  flex: 1;
  overflow-y: auto;
  padding: 28px 24px 48px;
}

.detail > * {
  max-width: 1000px;
  margin-left: auto;
  margin-right: auto;
}

.crumbs {
  font-size: 13px;
}

.card {
  margin-top: 14px;
  padding: 20px 24px;
  background: var(--tb-surface);
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius);
  box-shadow: var(--tb-shadow);
}

h1 {
  font-size: 20px;
  font-weight: 700;
  color: var(--tb-blue-900);
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

h3 {
  margin-top: 14px;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--tb-blue-900);
  display: flex;
  align-items: center;
  gap: 10px;
}

.mono-id {
  font-family: var(--tb-mono);
  font-size: 12px;
  font-weight: 400;
  color: var(--tb-text-3);
}

table {
  margin-top: 10px;
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

th,
td {
  padding: 7px 12px;
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

.remark {
  color: var(--tb-text-3);
  font-size: 12px;
}

.status {
  padding: 1px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
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

.kv {
  margin-top: 10px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 6px 24px;
  font-size: 13.5px;
}

.kv .k {
  display: inline-block;
  min-width: 76px;
  color: var(--tb-text-3);
}

.chapter {
  margin-top: 12px;
  padding: 12px 16px;
  border: 1px solid var(--tb-border);
  border-radius: 8px;
}

.chapter-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.chapter-head h4 {
  font-size: 14px;
  font-weight: 600;
}

.tag {
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11.5px;
  color: var(--tb-blue-600);
  background: var(--tb-blue-50);
}

.guidance {
  margin-top: 6px;
  font-size: 13px;
  color: var(--tb-text-2);
}

.style-note {
  margin-top: 4px;
  font-size: 12.5px;
  color: var(--tb-text-3);
}

.metric-ids {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--tb-text-2);
}

.metric-ids code {
  margin-right: 8px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--tb-blue-50);
  color: var(--tb-blue-600);
  font-family: var(--tb-mono);
  font-size: 11.5px;
}

.raw {
  margin-top: 14px;
}

.raw summary {
  cursor: pointer;
  color: var(--tb-blue-600);
  font-size: 13px;
  user-select: none;
}

.raw pre {
  margin-top: 8px;
  padding: 12px 14px;
  background: #0f1c30;
  color: #d5e3f8;
  border-radius: 8px;
  font-family: var(--tb-mono);
  font-size: 12px;
  line-height: 1.55;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.switch {
  display: inline-flex;
  gap: 6px;
}

.btn-ghost {
  padding: 3px 12px;
  border: 1px solid var(--tb-border);
  border-radius: 7px;
  background: var(--tb-surface);
  color: var(--tb-text-2);
  font-size: 12.5px;
  cursor: pointer;
}

.btn-ghost:hover:not(:disabled) {
  border-color: var(--tb-blue-500);
  color: var(--tb-blue-600);
}

.btn-ghost.on {
  border-color: var(--tb-blue-500);
  color: var(--tb-blue-700);
  background: var(--tb-blue-50);
  font-weight: 600;
}

.btn-ghost:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.hint {
  margin-top: 10px;
  color: var(--tb-text-3);
  font-size: 13px;
}

.err {
  margin-top: 10px;
  color: var(--tb-red);
  font-size: 13.5px;
}

.ok {
  margin-top: 10px;
  color: var(--tb-green);
  font-size: 13.5px;
  font-weight: 500;
}

.err-details {
  margin-top: 6px;
  padding-left: 20px;
  color: var(--tb-red);
  font-size: 12.5px;
}

.ops {
  white-space: nowrap;
}

.ops .btn-ghost {
  margin-right: 6px;
}

.op-pub {
  border-color: var(--tb-green);
  color: var(--tb-green);
}

.op-pub:hover:not(:disabled) {
  background: var(--tb-green-bg);
  border-color: var(--tb-green);
  color: var(--tb-green);
}

.op-dep {
  border-color: var(--tb-red);
  color: var(--tb-red);
}

.op-dep:hover:not(:disabled) {
  background: var(--tb-red-bg);
  border-color: var(--tb-red);
  color: var(--tb-red);
}

.confirm-bar {
  margin-top: 14px;
  padding: 12px 16px;
  background: var(--tb-blue-50);
  border: 1px solid var(--tb-blue-100);
  border-radius: 8px;
  font-size: 13.5px;
}

.confirm-bar .warn {
  margin-top: 6px;
  color: var(--tb-amber);
  font-size: 13px;
}

.confirm-actions {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.op-input {
  width: 190px;
  padding: 5px 12px;
  border: 1px solid var(--tb-border);
  border-radius: 7px;
  font-family: var(--tb-font);
  font-size: 13px;
  outline: none;
}

.op-input:focus {
  border-color: var(--tb-blue-500);
}

.btn-primary {
  padding: 6px 16px;
  border: none;
  border-radius: 7px;
  background: var(--tb-blue-600);
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.btn-primary:hover:not(:disabled) {
  background: var(--tb-blue-700);
}

.btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

code {
  font-family: var(--tb-mono);
  font-size: 12px;
}
</style>
