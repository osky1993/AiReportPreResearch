<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  AssetApiError,
  createTemplate,
  draftTemplate,
  listMetricAssets,
  listTemplates,
  saveTemplateVersion,
  validateTemplate,
  type MetricSummary,
  type TemplateBody,
  type ValidationDetail,
} from '@/api/assets'

const PERIOD_TYPES = [
  { key: 'WEEK', label: '周报' },
  { key: 'MONTH', label: '月报' },
  { key: 'QUARTER', label: '季报' },
]

const COMPARISONS = [
  { key: 'month_over_month', label: '环比（较上月）' },
  { key: 'year_over_year', label: '同比（较上年同期）' },
]

/** 可编辑章节（与 ReportTemplateDef.Chapter 对齐；charts 原样透传不编辑） */
interface EditChapter {
  chapterId: string
  title: string
  guidance: string
  stylePrompt: string
  comparisons: string[]
  metricIds: string[]
  charts?: unknown[]
}

const metrics = ref<MetricSummary[]>([])
const existingIds = ref(new Set<string>())

// —— 起草区 ——
const description = ref('')
const author = ref('')
const drafting = ref(false)
const draftErr = ref('')
const draftErrDetails = ref<ValidationDetail[]>([])
const draftNotes = ref<string[]>([])
const draftUnresolved = ref<string[]>([])

// —— 编辑器 ——
const editing = ref(false)
const templateId = ref('')
const name = ref('')
const keywordsText = ref('')
const periodTypes = ref<string[]>(['MONTH'])
const chapters = ref<EditChapter[]>([])

// —— 校验/保存 ——
const validating = ref(false)
const validMsg = ref('')
const validErrors = ref<ValidationDetail[]>([])
const saving = ref(false)
const saveErr = ref('')
const saveErrDetails = ref<ValidationDetail[]>([])
const savedId = ref('')
const savedVersion = ref(0)
const remark = ref('')

function resetValidation() {
  validMsg.value = ''
  validErrors.value = []
  saveErr.value = ''
  saveErrDetails.value = []
}

function buildBody(): TemplateBody {
  return {
    templateId: templateId.value.trim(),
    name: name.value.trim(),
    keywords: keywordsText.value
      .split(/[,，、\s]+/)
      .map((s) => s.trim())
      .filter(Boolean),
    periodTypes: [...periodTypes.value],
    chapters: chapters.value.map((c) => ({
      chapterId: c.chapterId.trim(),
      title: c.title.trim(),
      guidance: c.guidance.trim(),
      ...(c.stylePrompt.trim() ? { stylePrompt: c.stylePrompt.trim() } : {}),
      comparisons: [...c.comparisons],
      metrics: [...c.metricIds],
      ...(c.charts?.length ? { charts: c.charts } : {}),
    })),
  } as TemplateBody
}

function loadIntoEditor(body: TemplateBody) {
  templateId.value = body.templateId ?? ''
  name.value = body.name ?? ''
  keywordsText.value = (body.keywords ?? []).join('、')
  periodTypes.value = body.periodTypes?.length ? [...body.periodTypes] : ['MONTH']
  chapters.value = (body.chapters ?? []).map((c) => ({
    chapterId: c.chapterId ?? '',
    title: c.title ?? '',
    guidance: c.guidance ?? '',
    stylePrompt: c.stylePrompt ?? '',
    comparisons: c.comparisons?.length ? [...c.comparisons] : c.comparison ? [c.comparison] : [],
    metricIds: [...(c.metrics ?? [])],
    charts: c.charts as unknown[] | undefined,
  }))
  editing.value = true
  resetValidation()
}

async function doDraft() {
  if (!description.value.trim() || drafting.value) return
  drafting.value = true
  draftErr.value = ''
  draftErrDetails.value = []
  draftNotes.value = []
  draftUnresolved.value = []
  try {
    const r = await draftTemplate(description.value.trim(), author.value.trim() || '业务用户')
    loadIntoEditor(r.draft)
    draftNotes.value = r.notes ?? []
    draftUnresolved.value = r.unresolved ?? []
  } catch (e) {
    draftErr.value = e instanceof Error ? e.message : '起草失败'
    draftErrDetails.value = e instanceof AssetApiError ? e.details : []
  } finally {
    drafting.value = false
  }
}

function startBlank() {
  loadIntoEditor({
    templateId: '',
    name: '',
    keywords: [],
    periodTypes: ['MONTH'],
    chapters: [],
  } as TemplateBody)
}

function addChapter() {
  chapters.value.push({
    chapterId: `ch_${chapters.value.length + 1}`,
    title: '',
    guidance: '',
    stylePrompt: '',
    comparisons: [],
    metricIds: [],
  })
}

function removeChapter(i: number) {
  chapters.value.splice(i, 1)
}

function toggleIn(list: string[], v: string) {
  const i = list.indexOf(v)
  if (i >= 0) list.splice(i, 1)
  else list.push(v)
}

async function doValidate() {
  if (validating.value) return
  validating.value = true
  resetValidation()
  try {
    const r = await validateTemplate(buildBody())
    if (r.valid) validMsg.value = '校验通过，可保存为草稿'
    else validErrors.value = r.errors ?? []
  } catch (e) {
    validErrors.value = e instanceof AssetApiError ? e.details : []
    validMsg.value = ''
    saveErr.value = e instanceof Error ? e.message : '校验失败'
  } finally {
    validating.value = false
  }
}

async function doSave() {
  if (saving.value) return
  saving.value = true
  saveErr.value = ''
  saveErrDetails.value = []
  try {
    const body = buildBody()
    const by = author.value.trim() || '业务用户'
    const rm = remark.value.trim() || '业务前端制作'
    const r = existingIds.value.has(body.templateId)
      ? await saveTemplateVersion(body.templateId, body, by, rm)
      : await createTemplate(body, by, rm)
    savedId.value = body.templateId
    savedVersion.value = r.version
  } catch (e) {
    saveErr.value = e instanceof Error ? e.message : '保存失败'
    saveErrDetails.value = e instanceof AssetApiError ? e.details : []
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  try {
    metrics.value = await listMetricAssets()
    existingIds.value = new Set((await listTemplates()).map((t) => t.templateId))
  } catch {
    /* 指标清单加载失败时编辑器仍可用（显示原始 id） */
  }
})
</script>

<template>
  <main class="author">
    <p class="crumbs"><RouterLink to="/assets/templates">← 返回资产列表</RouterLink></p>

    <section class="head">
      <h1>模板制作</h1>
      <p>
        用一句话描述报告诉求，AI 起草模板草案（只会引用已有指标，不会发明数据）；
        人工调整并通过校验后保存为草稿，再到资产列表发布上线。
      </p>
    </section>

    <!-- ① AI 起草 -->
    <section class="card">
      <h2>① 描述报告诉求</h2>
      <textarea
        v-model="description"
        rows="2"
        placeholder="例：每周给资金部领导看的外汇风险情况报告，重点是外币头寸、跨境收支和大额交易"
      ></textarea>
      <div class="row">
        <input v-model="author" class="inp name-inp" placeholder="制作人（默认：业务用户）" />
        <button class="btn-primary" :disabled="drafting || !description.trim()" @click="doDraft">
          {{ drafting ? 'AI 起草中，约 5~20 秒…' : 'AI 起草' }}
        </button>
        <button class="btn-ghost" :disabled="drafting" @click="startBlank">从空白开始</button>
      </div>
      <p v-if="draftErr" class="err">{{ draftErr }}</p>
      <ul v-if="draftErrDetails.length" class="err-details">
        <li v-for="(d, i) in draftErrDetails" :key="i">
          <code>{{ d.location }}</code
          >：{{ d.message }}
        </li>
      </ul>
      <div v-if="draftUnresolved.length" class="warn-box">
        ⚠️ 以下表述映射不到已有指标（不猜测补全，可先去制作指标）：
        <ul>
          <li v-for="(u, i) in draftUnresolved" :key="i">{{ u }}</li>
        </ul>
      </div>
      <ul v-if="draftNotes.length" class="notes">
        <li v-for="(n, i) in draftNotes" :key="i">💡 {{ n }}</li>
      </ul>
    </section>

    <!-- ② 编辑器 -->
    <section v-if="editing" class="card">
      <h2>② 调整模板定义</h2>
      <div class="grid2">
        <label class="field">
          <span>模板 ID（英文短横线，如 gk-balance-quarterly）</span>
          <input v-model="templateId" class="inp" @input="resetValidation" />
          <span v-if="existingIds.has(templateId.trim())" class="field-note">
            该 ID 已存在，保存将产生新版本草稿
          </span>
        </label>
        <label class="field">
          <span>模板名称</span>
          <input v-model="name" class="inp" @input="resetValidation" />
        </label>
        <label class="field">
          <span>匹配关键词（顿号/逗号分隔，用于需求→模板召回）</span>
          <input v-model="keywordsText" class="inp" @input="resetValidation" />
        </label>
        <div class="field">
          <span>适用周期</span>
          <div class="checks">
            <label v-for="p in PERIOD_TYPES" :key="p.key">
              <input
                type="checkbox"
                :checked="periodTypes.includes(p.key)"
                @change="toggleIn(periodTypes, p.key), resetValidation()"
              />
              {{ p.label }}
            </label>
          </div>
        </div>
      </div>

      <div v-for="(c, ci) in chapters" :key="ci" class="chapter">
        <div class="chapter-head">
          <h3>第 {{ ci + 1 }} 章</h3>
          <input v-model="c.chapterId" class="inp id-inp" placeholder="章节 ID" @input="resetValidation" />
          <input v-model="c.title" class="inp title-inp" placeholder="章节标题" @input="resetValidation" />
          <button class="btn-ghost op-dep" @click="removeChapter(ci), resetValidation()">删除本章</button>
        </div>
        <label class="field">
          <span>写作指引（本章讲什么、怎么讲、哪些不许推断）</span>
          <textarea v-model="c.guidance" rows="2" @input="resetValidation"></textarea>
        </label>
        <label class="field">
          <span>文风提示（可空，只影响措辞、改不动数字）</span>
          <input v-model="c.stylePrompt" class="inp" @input="resetValidation" />
        </label>
        <div class="field">
          <span>对比口径</span>
          <div class="checks">
            <label v-for="cmp in COMPARISONS" :key="cmp.key">
              <input
                type="checkbox"
                :checked="c.comparisons.includes(cmp.key)"
                @change="toggleIn(c.comparisons, cmp.key), resetValidation()"
              />
              {{ cmp.label }}
            </label>
          </div>
        </div>
        <div class="field">
          <span>本章指标（勾选已发布指标口径）</span>
          <div class="checks metrics-checks">
            <label v-for="m in metrics" :key="m.metricId" :title="m.metricId">
              <input
                type="checkbox"
                :checked="c.metricIds.includes(m.metricId)"
                @change="toggleIn(c.metricIds, m.metricId), resetValidation()"
              />
              {{ m.name }}
            </label>
          </div>
          <span v-if="c.metricIds.some((id) => !metrics.find((m) => m.metricId === id))" class="field-note">
            含未在清单中的指标：{{ c.metricIds.filter((id) => !metrics.find((m) => m.metricId === id)).join('、') }}
          </span>
        </div>
      </div>
      <button class="btn-ghost" @click="addChapter">＋ 添加章节</button>
    </section>

    <!-- ③ 校验与保存 -->
    <section v-if="editing" class="card">
      <h2>③ 校验并保存草稿</h2>
      <div class="row">
        <button class="btn-ghost" :disabled="validating" @click="doValidate">
          {{ validating ? '校验中…' : '干跑校验' }}
        </button>
        <input v-model="remark" class="inp" style="flex: 1; min-width: 200px" placeholder="版本备注（可空）" />
        <button class="btn-primary" :disabled="saving" @click="doSave">
          {{ saving ? '保存中…' : '保存为草稿' }}
        </button>
      </div>
      <p v-if="validMsg" class="ok">✓ {{ validMsg }}</p>
      <ul v-if="validErrors.length" class="err-details">
        <li v-for="(d, i) in validErrors" :key="i">
          <code>{{ d.location }}</code
          >：{{ d.message }}
        </li>
      </ul>
      <p v-if="saveErr" class="err">{{ saveErr }}</p>
      <ul v-if="saveErrDetails.length" class="err-details">
        <li v-for="(d, i) in saveErrDetails" :key="i">
          <code>{{ d.location }}</code
          >：{{ d.message }}
        </li>
      </ul>
      <div v-if="savedId" class="saved-box">
        ✓ 已保存草稿 v{{ savedVersion }}。草稿不会用于报告生成，到
        <RouterLink :to="`/assets/templates/${savedId}`">资产详情页</RouterLink>
        发布后生效。
      </div>
    </section>
  </main>
</template>

<style scoped>
.author {
  flex: 1;
  overflow-y: auto;
  padding: 28px 24px 48px;
}

.author > * {
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

.card {
  margin-top: 16px;
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
  padding: 10px 14px;
  border: 1px solid var(--tb-border);
  border-radius: 8px;
  font-family: var(--tb-font);
  font-size: 13.5px;
  line-height: 1.5;
  color: var(--tb-text);
  outline: none;
}

textarea:focus {
  border-color: var(--tb-blue-500);
}

.inp {
  padding: 7px 12px;
  border: 1px solid var(--tb-border);
  border-radius: 7px;
  font-family: var(--tb-font);
  font-size: 13px;
  color: var(--tb-text);
  outline: none;
}

.inp:focus {
  border-color: var(--tb-blue-500);
}

.name-inp {
  width: 190px;
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

.btn-ghost:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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
  margin-top: 12px;
  padding: 10px 14px;
  background: var(--tb-amber-bg);
  border-radius: 8px;
  font-size: 13px;
  color: var(--tb-amber);
}

.warn-box ul {
  margin: 4px 0 0;
  padding-left: 20px;
}

.notes {
  margin-top: 10px;
  padding-left: 4px;
  list-style: none;
  color: var(--tb-text-2);
  font-size: 12.5px;
}

.grid2 {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
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
  color: var(--tb-amber);
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
  user-select: none;
}

.metrics-checks {
  padding: 8px 12px;
  border: 1px solid var(--tb-border);
  border-radius: 8px;
  max-height: 180px;
  overflow-y: auto;
}

.chapter {
  margin-top: 14px;
  padding: 14px 16px;
  border: 1px solid var(--tb-border);
  border-radius: 8px;
}

.chapter-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.chapter-head h3 {
  font-size: 14px;
  font-weight: 600;
  color: var(--tb-blue-900);
}

.id-inp {
  width: 130px;
  font-family: var(--tb-mono);
  font-size: 12px;
}

.title-inp {
  flex: 1;
  min-width: 200px;
}

.chapter-head .btn-ghost {
  margin-left: auto;
  padding: 4px 12px;
  font-size: 12px;
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
