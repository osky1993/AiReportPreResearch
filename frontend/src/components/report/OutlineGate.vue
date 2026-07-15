<script setup lang="ts">
import { ref } from 'vue'
import type { Outline } from '@/api/report'

const props = defineProps<{
  outline: Outline
  /** metricId → 业务名称（映射不到时回退显示 id） */
  metricNames: Record<string, string>
  busy: boolean
}>()

const emit = defineEmits<{
  approve: [approver: string, outline: Outline]
  regenerate: [revisedRequest: string]
}>()

const approver = ref('')
const revise = ref('')
/** 被勾掉的指标，键 `${chapterIdx}|${metricId}`（本地编辑态，仅本状态有效） */
const off = ref(new Set<string>())

function isOn(ci: number, m: string): boolean {
  return !off.value.has(`${ci}|${m}`)
}

function toggle(ci: number, m: string) {
  const key = `${ci}|${m}`
  if (off.value.has(key)) off.value.delete(key)
  else off.value.add(key)
  off.value = new Set(off.value)
}

function metricLabel(m: string): string {
  return props.metricNames[m] ?? m
}

const CMP_LABELS: Record<string, string> = {
  month_over_month: '环比（较上月）',
  year_over_year: '同比（较上年同期）',
}

function cmpLabel(c: string): string {
  return CMP_LABELS[c] ?? c
}

function doApprove() {
  const copy = JSON.parse(JSON.stringify(props.outline)) as Outline
  copy.chapters.forEach((c, ci) => {
    c.metricIds = c.metricIds.filter((m) => isOn(ci, m))
  })
  emit('approve', approver.value.trim() || '业务用户', copy)
}
</script>

<template>
  <section class="gate">
    <h2>🔒 卡点1 · 确认报告口径</h2>
    <p class="gate-sub">
      请核对以下章节与指标口径。<b>确认后口径锁死</b>，之后的取数、成文、核数全部由程序完成，不再引入人为变化。
    </p>

    <div v-if="outline.unresolved?.length" class="unres">
      ⚠️ 以下需求表述映射不到已定义的指标口径（系统不猜测补全）：
      <ul>
        <li v-for="(u, i) in outline.unresolved" :key="i">{{ u }}</li>
      </ul>
      可忽略继续，或在打回意见中澄清后重新生成大纲。
    </div>

    <div v-for="(c, ci) in outline.chapters" :key="c.chapterId" class="chapter">
      <div class="chapter-head">
        <h3>{{ c.title }}</h3>
        <span v-if="c.comparison && !c.comparisons?.length" class="tag">环比（较上月）</span>
        <span v-for="cmp in c.comparisons ?? []" :key="cmp" class="tag">{{ cmpLabel(cmp) }}</span>
      </div>
      <p v-if="c.guidance" class="guidance">{{ c.guidance }}</p>
      <p v-if="c.stylePrompt" class="style-note">✍️ 文风要求：{{ c.stylePrompt }}</p>
      <div class="metrics">
        <label v-for="m in c.metricIds" :key="m" :class="{ off: !isOn(ci, m) }">
          <input
            type="checkbox"
            :checked="isOn(ci, m)"
            :disabled="busy"
            @change="toggle(ci, m)"
          />
          {{ metricLabel(m) }}
        </label>
      </div>
    </div>

    <div class="actions">
      <input v-model="approver" class="name-input" placeholder="确认人（默认：业务用户）" />
      <button class="btn-primary" :disabled="busy" @click="doApprove">
        {{ busy ? '处理中…' : '确认口径，开始生成' }}
      </button>
    </div>
    <div class="actions">
      <input
        v-model="revise"
        class="revise-input"
        placeholder="打回意见（如：改为 2026 年 5 月）"
      />
      <button class="btn-danger" :disabled="busy" @click="emit('regenerate', revise.trim())">
        打回重新生成
      </button>
    </div>
  </section>
</template>

<style scoped>
.gate {
  margin-top: 16px;
  padding: 20px 24px;
  background: var(--tb-surface);
  border: 1px solid var(--tb-amber);
  border-radius: var(--tb-radius);
  box-shadow: var(--tb-shadow);
}

.gate h2 {
  font-size: 16px;
  font-weight: 600;
  color: var(--tb-blue-900);
}

.gate-sub {
  margin-top: 6px;
  font-size: 13px;
  color: var(--tb-text-2);
}

.unres {
  margin-top: 12px;
  padding: 10px 14px;
  background: var(--tb-amber-bg);
  border-radius: 8px;
  font-size: 13px;
  color: var(--tb-amber);
}

.unres ul {
  margin: 4px 0;
  padding-left: 20px;
}

.chapter {
  margin-top: 14px;
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

.chapter-head h3 {
  font-size: 14.5px;
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

.metrics {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
}

.metrics label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  cursor: pointer;
  user-select: none;
}

.metrics label.off {
  color: var(--tb-text-3);
  text-decoration: line-through;
}

.actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.name-input,
.revise-input {
  padding: 6px 12px;
  border: 1px solid var(--tb-border);
  border-radius: 7px;
  font-family: var(--tb-font);
  font-size: 13px;
  outline: none;
}

.name-input {
  width: 200px;
}

.revise-input {
  flex: 1;
  min-width: 220px;
}

.name-input:focus,
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

.btn-danger:hover:not(:disabled) {
  background: var(--tb-red-bg);
}

.btn-primary:disabled,
.btn-danger:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
</style>
