<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  AssetApiError,
  createMetric,
  explainMql,
  getCaliber,
  parameterizeApply,
  parameterizeScan,
  tryMetric,
  type ParamSuggestion,
  type TryResult,
  type ValidationDetail,
} from '@/api/assets'

const route = useRoute()

const step = ref(1)
/** 来自问数沉淀口径的升格（跳过①②，MQL 已人工核验） */
const fromCaliber = ref<number | null>(null)

// ① 试查
const question = ref('')
const trying = ref(false)
const tryRes = ref<TryResult | null>(null)
const tryErr = ref('')

// ② 反翻译
const explaining = ref(false)
const explanation = ref('')
const caveats = ref<string[]>([])
const explainErr = ref('')

// ③ 参数化
const scanning = ref(false)
const suggestions = ref<ParamSuggestion[]>([])
const scanNotes = ref<string[]>([])
const chosen = ref(new Set<string>())
const applying = ref(false)
const mqlTemplate = ref<Record<string, unknown> | null>(null)
const applied = ref<string[]>([])
const remaining = ref<ParamSuggestion[]>([])
const paramErr = ref('')

// ④ 元数据
const metricId = ref('')
const name = ref('')
const unit = ref('CNY')
const valueColumn = ref('')
const comparable = ref(true)
const nullPolicy = ref('BLOCK')
const nonNegative = ref(true)

// ⑤ 保存
const saving = ref(false)
const saveErr = ref('')
const saveErrDetails = ref<ValidationDetail[]>([])
const savedVersion = ref(0)

const mql = computed(() => tryRes.value?.mql ?? null)
/** 有占位符应用即视为按报告期取数 */
const timeBound = computed(() => applied.value.length > 0)

function toggleChosen(path: string) {
  const next = new Set(chosen.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  chosen.value = next
}

async function doTry() {
  if (!question.value.trim() || trying.value) return
  trying.value = true
  tryErr.value = ''
  tryRes.value = null
  try {
    tryRes.value = await tryMetric(question.value.trim())
    if (tryRes.value.success) step.value = 2
  } catch (e) {
    tryErr.value = e instanceof Error ? e.message : '试查失败'
  } finally {
    trying.value = false
  }
}

async function doExplain() {
  if (!mql.value || explaining.value) return
  explaining.value = true
  explainErr.value = ''
  try {
    const r = await explainMql(mql.value)
    explanation.value = r.explanation
    caveats.value = r.caveats ?? []
  } catch (e) {
    explainErr.value = e instanceof Error ? e.message : '反翻译失败'
  } finally {
    explaining.value = false
  }
}

async function doScan() {
  if (!mql.value || scanning.value) return
  scanning.value = true
  paramErr.value = ''
  try {
    const r = await parameterizeScan(mql.value)
    suggestions.value = r.suggestions ?? []
    scanNotes.value = r.notes ?? []
    chosen.value = new Set(suggestions.value.map((s) => s.path))
    if (!suggestions.value.length) {
      mqlTemplate.value = mql.value
      applied.value = []
      remaining.value = []
    }
  } catch (e) {
    paramErr.value = e instanceof Error ? e.message : '参数化扫描失败'
  } finally {
    scanning.value = false
  }
}

async function doApply() {
  if (!mql.value || applying.value) return
  applying.value = true
  paramErr.value = ''
  try {
    const r = await parameterizeApply(mql.value, [...chosen.value])
    mqlTemplate.value = r.mqlTemplate
    applied.value = r.applied ?? []
    remaining.value = r.remaining ?? []
    step.value = 4
  } catch (e) {
    paramErr.value = e instanceof Error ? e.message : '参数化失败'
  } finally {
    applying.value = false
  }
}

function skipParam() {
  if (!mql.value) return
  mqlTemplate.value = mql.value
  applied.value = []
  remaining.value = suggestions.value
  step.value = 4
}

function toStep4() {
  // 取值列缺省猜第一个数值列 / 唯一列
  if (!valueColumn.value && tryRes.value?.columns?.length) {
    valueColumn.value = tryRes.value.columns[tryRes.value.columns.length - 1]!
  }
  step.value = 4
}

async function doSave() {
  if (saving.value || !mqlTemplate.value) return
  saving.value = true
  saveErr.value = ''
  saveErrDetails.value = []
  try {
    const r = await createMetric(
      {
        metricId: metricId.value.trim(),
        name: name.value.trim(),
        unit: unit.value.trim(),
        timeBound: timeBound.value,
        comparable: comparable.value,
        valueColumn: valueColumn.value.trim(),
        nullPolicy: nullPolicy.value,
        qualityChecks: nonNegative.value ? ['NON_NEGATIVE'] : [],
        mqlTemplate: mqlTemplate.value,
      },
      question.value.trim(),
      '业务用户',
    )
    savedVersion.value = r.version
    step.value = 6
  } catch (e) {
    saveErr.value = e instanceof Error ? e.message : '保存失败'
    saveErrDetails.value = e instanceof AssetApiError ? e.details : []
  } finally {
    saving.value = false
  }
}

function fmtCell(v: unknown): string {
  if (v == null) return '—'
  return String(v)
}

onMounted(async () => {
  const cid = route.query.caliber
  if (cid) {
    try {
      const c = await getCaliber(String(cid))
      fromCaliber.value = c.id
      question.value = c.question
      tryRes.value = {
        mql: JSON.parse(c.mqlJson) as Record<string, unknown>,
        sql: null,
        rows: null,
        columns: null,
        success: true,
        errors: null,
        warnings: null,
        clarifyReason: null,
      }
      step.value = 3
      doScan()
    } catch {
      /* 口径加载失败则按普通向导从①开始 */
    }
  }
})
</script>

<template>
  <main class="wizard">
    <p class="crumbs"><RouterLink to="/assets/metrics">← 返回资产列表</RouterLink></p>

    <section class="head">
      <h1>指标制作向导</h1>
      <p>
        试查出数 → 人工核对口径 → 参数化沉淀为确定性取数模板。保存时服务端重跑五类校验，全部通过才能落为草稿。
      </p>
      <p v-if="fromCaliber" class="from-note">
        ⬆️ 正在把问数沉淀口径 #{{ fromCaliber }} 升格为指标（MQL 已人工核验，跳过试查）
      </p>
    </section>

    <!-- ① 试查 -->
    <section class="card" :class="{ dim: step > 1 }">
      <h2>① 用一句话试查出数 <span v-if="fromCaliber" class="skip">已跳过（来自已核验口径）</span></h2>
      <template v-if="!fromCaliber">
        <textarea
          v-model="question"
          rows="2"
          placeholder="例：2026年6月22日到6月28日投资类支出折人民币总金额（不含失败交易）"
        ></textarea>
        <div class="row">
          <button class="btn-primary" :disabled="trying || !question.trim()" @click="doTry">
            {{ trying ? '试查中，约 5~30 秒…' : '试查' }}
          </button>
          <span class="hint">试查走 AI 生成链路（制作期工具），结果需人工核验后才会沉淀。</span>
        </div>
        <p v-if="tryErr" class="err">{{ tryErr }}</p>
        <template v-if="tryRes">
          <p v-if="tryRes.clarifyReason" class="warn-box">{{ tryRes.clarifyReason }}</p>
          <ul v-if="tryRes.errors?.length" class="err-details">
            <li v-for="(e, i) in tryRes.errors" :key="i">{{ e }}</li>
          </ul>
          <template v-if="tryRes.success">
            <div v-if="tryRes.rows?.length" class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th v-for="col in tryRes.columns ?? []" :key="col">{{ col }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(r, ri) in tryRes.rows" :key="ri">
                    <td v-for="col in tryRes.columns ?? []" :key="col">{{ fmtCell(r[col]) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <details class="raw">
              <summary>查看生成的 SQL / MQL</summary>
              <pre>{{ tryRes.sql }}</pre>
              <pre>{{ JSON.stringify(tryRes.mql, null, 2) }}</pre>
            </details>
          </template>
        </template>
      </template>
    </section>

    <!-- ② 反翻译 -->
    <section v-if="step >= 2 && !fromCaliber" class="card" :class="{ dim: step > 3 }">
      <h2>② 口径反翻译（人工核对）</h2>
      <p class="hint">把生成的取数逻辑译回业务话术，请核对是否与您的本意一致。</p>
      <div class="row">
        <button class="btn-ghost" :disabled="explaining" @click="doExplain">
          {{ explaining ? '翻译中…' : explanation ? '重新翻译' : '开始反翻译' }}
        </button>
        <button class="btn-primary" :disabled="step > 2 && !!suggestions.length" @click="(step = 3), doScan()">
          口径无误，下一步参数化
        </button>
      </div>
      <p v-if="explainErr" class="err">{{ explainErr }}</p>
      <div v-if="explanation" class="explain-box">
        {{ explanation }}
        <ul v-if="caveats.length">
          <li v-for="(c, i) in caveats" :key="i">⚠ {{ c }}</li>
        </ul>
      </div>
    </section>

    <!-- ③ 参数化 -->
    <section v-if="step >= 3" class="card" :class="{ dim: step > 3 }">
      <h2>③ 参数化（把具体日期替换为报告期占位符）</h2>
      <p class="hint">
        勾选的日期字面量将替换为 {{ '\{\{period_start\}\}' }}/{{ '\{\{period_end\}\}' }}——
        同一模板即可按任意报告期确定性取数。
      </p>
      <p v-if="paramErr" class="err">{{ paramErr }}</p>
      <p v-if="scanning" class="hint">扫描中…</p>
      <template v-else-if="suggestions.length">
        <div v-for="s in suggestions" :key="s.path" class="sug">
          <label>
            <input type="checkbox" :checked="chosen.has(s.path)" @change="toggleChosen(s.path)" />
            <code>{{ s.field }} {{ s.op }} {{ s.value }}</code> → <code>{{ s.placeholder }}</code>
          </label>
          <span class="reason">{{ s.reason }}</span>
        </div>
        <div class="row">
          <button class="btn-primary" :disabled="applying || chosen.size === 0" @click="doApply">
            {{ applying ? '应用中…' : '应用参数化' }}
          </button>
          <button class="btn-ghost" @click="skipParam">不参数化（快照类指标）</button>
        </div>
      </template>
      <template v-else-if="!scanning && mqlTemplate">
        <p class="ok">✓ 未发现日期字面量，将按快照类指标（timeBound=false）处理。</p>
        <div class="row">
          <button class="btn-primary" @click="toStep4">下一步</button>
        </div>
      </template>
      <ul v-if="scanNotes.length" class="notes">
        <li v-for="(n, i) in scanNotes" :key="i">💡 {{ n }}</li>
      </ul>
      <p v-if="applied.length" class="ok">✓ 已替换 {{ applied.length }} 处占位符</p>
    </section>

    <!-- ④ 元数据 -->
    <section v-if="step >= 4" class="card" :class="{ dim: step > 5 }">
      <h2>④ 指标口径元数据</h2>
      <div class="grid2">
        <label class="field">
          <span>指标 ID（英文下划线，如 gk_eom_balance_total）</span>
          <input v-model="metricId" class="inp" />
        </label>
        <label class="field">
          <span>指标名称（业务命名）</span>
          <input v-model="name" class="inp" />
        </label>
        <label class="field">
          <span>单位</span>
          <input v-model="unit" class="inp" />
        </label>
        <label class="field">
          <span>取值列（结果中承载指标值的列名）</span>
          <input v-model="valueColumn" class="inp" :placeholder="(tryRes?.columns ?? []).join(' / ')" />
        </label>
        <div class="field">
          <span>属性</span>
          <div class="checks">
            <label><input v-model="comparable" type="checkbox" /> 可对比（支持环比/同比派生）</label>
            <label><input v-model="nonNegative" type="checkbox" /> 质量断言：不允许负值</label>
          </div>
          <span class="field-note">按报告期取数（timeBound）：{{ timeBound ? '是（已参数化）' : '否（快照类）' }}</span>
        </div>
        <label class="field">
          <span>取数为空时</span>
          <select v-model="nullPolicy" class="inp">
            <option value="BLOCK">失败关闭（BLOCK，转人工）</option>
            <option value="ZERO">按 0 处理（ZERO，如空窗口求和）</option>
          </select>
        </label>
      </div>
      <details v-if="mqlTemplate" class="raw">
        <summary>确认取数模板（MQL）</summary>
        <pre>{{ JSON.stringify(mqlTemplate, null, 2) }}</pre>
      </details>
      <div class="row">
        <button
          class="btn-primary"
          :disabled="!metricId.trim() || !name.trim() || !valueColumn.trim()"
          @click="step = 5"
        >
          下一步：保存
        </button>
      </div>
    </section>

    <!-- ⑤ 保存 -->
    <section v-if="step >= 5" class="card">
      <h2>⑤ 保存为指标草稿</h2>
      <p class="hint">
        保存时服务端重跑五类校验（结构 / 占位符 / MQL 白名单 / 试执行 / 结果形状），全部通过才落库。
      </p>
      <div class="row">
        <button class="btn-primary" :disabled="saving || step >= 6" @click="doSave">
          {{ saving ? '校验并保存中…' : '保存草稿' }}
        </button>
      </div>
      <p v-if="saveErr" class="err">{{ saveErr }}</p>
      <ul v-if="saveErrDetails.length" class="err-details">
        <li v-for="(d, i) in saveErrDetails" :key="i">
          <code>{{ d.location }}</code
          >：{{ d.message }}
        </li>
      </ul>
      <div v-if="step >= 6" class="saved-box">
        ✓ 指标草稿 v{{ savedVersion }} 已保存。到
        <RouterLink :to="`/assets/metrics/${metricId.trim()}`">资产详情页</RouterLink>
        发布后即可在模板中引用。
      </div>
    </section>
  </main>
</template>

<style scoped>
.wizard {
  flex: 1;
  overflow-y: auto;
  padding: 28px 24px 48px;
}

.wizard > * {
  max-width: 900px;
  margin-left: auto;
  margin-right: auto;
}

.crumbs {
  font-size: 13px;
}

.head {
  text-align: center;
  padding: 12px 0 4px;
}

.head h1 {
  font-size: 24px;
  color: var(--tb-blue-900);
  font-weight: 700;
}

.head p {
  margin-top: 8px;
  color: var(--tb-text-2);
  font-size: 13.5px;
}

.from-note {
  color: var(--tb-blue-600) !important;
  font-weight: 500;
}

.card {
  margin-top: 16px;
  padding: 20px 24px;
  background: var(--tb-surface);
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius);
  box-shadow: var(--tb-shadow);
}

.card.dim {
  opacity: 0.75;
}

.card h2 {
  font-size: 16px;
  font-weight: 600;
  color: var(--tb-blue-900);
  margin-bottom: 10px;
}

.skip {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: var(--tb-text-3);
}

textarea {
  width: 100%;
  resize: vertical;
  padding: 10px 14px;
  border: 1px solid var(--tb-border);
  border-radius: 8px;
  font-family: var(--tb-font);
  font-size: 13.5px;
  outline: none;
}

textarea:focus {
  border-color: var(--tb-blue-500);
}

.row {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.btn-primary {
  padding: 8px 20px;
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

.btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.btn-ghost {
  padding: 7px 16px;
  border: 1px solid var(--tb-border);
  border-radius: 8px;
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
  font-size: 13.5px;
}

.ok {
  margin-top: 10px;
  color: var(--tb-green);
  font-size: 13.5px;
  font-weight: 500;
}

.err-details {
  margin-top: 8px;
  padding-left: 20px;
  color: var(--tb-red);
  font-size: 12.5px;
}

.err-details code {
  font-family: var(--tb-mono);
  font-size: 11.5px;
}

.warn-box {
  margin-top: 10px;
  padding: 10px 14px;
  background: var(--tb-amber-bg);
  border-radius: 8px;
  font-size: 13px;
  color: var(--tb-amber);
}

.notes {
  margin-top: 10px;
  padding-left: 4px;
  list-style: none;
  color: var(--tb-text-2);
  font-size: 12.5px;
}

.table-wrap {
  margin-top: 12px;
  overflow-x: auto;
  border: 1px solid var(--tb-border);
  border-radius: 8px;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  white-space: nowrap;
}

th,
td {
  padding: 7px 12px;
  text-align: left;
  border-bottom: 1px solid var(--tb-border);
}

th {
  background: var(--tb-blue-50);
  color: var(--tb-blue-900);
  font-weight: 600;
}

tbody tr:last-child td {
  border-bottom: none;
}

.raw {
  margin-top: 12px;
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

.explain-box {
  margin-top: 12px;
  padding: 12px 16px;
  background: var(--tb-blue-50);
  border-radius: 8px;
  font-size: 13.5px;
  line-height: 1.7;
}

.explain-box ul {
  margin-top: 6px;
  padding-left: 18px;
  color: var(--tb-amber);
  font-size: 12.5px;
}

.sug {
  margin-top: 8px;
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}

.sug label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  cursor: pointer;
}

.sug code {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--tb-blue-50);
  color: var(--tb-blue-700);
  font-family: var(--tb-mono);
  font-size: 12px;
}

.sug .reason {
  font-size: 12px;
  color: var(--tb-text-3);
}

.grid2 {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 12px 24px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
}

.field > span {
  font-size: 12.5px;
  color: var(--tb-text-3);
}

.field-note {
  font-size: 12px;
  color: var(--tb-text-2);
}

.inp {
  padding: 7px 12px;
  border: 1px solid var(--tb-border);
  border-radius: 7px;
  font-family: var(--tb-font);
  font-size: 13px;
  outline: none;
  background: var(--tb-surface);
}

.inp:focus {
  border-color: var(--tb-blue-500);
}

.checks {
  display: flex;
  gap: 6px 18px;
  flex-wrap: wrap;
}

.checks label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  cursor: pointer;
}

.saved-box {
  margin-top: 12px;
  padding: 12px 16px;
  background: var(--tb-green-bg);
  border-radius: 8px;
  color: var(--tb-green);
  font-size: 13.5px;
}
</style>
