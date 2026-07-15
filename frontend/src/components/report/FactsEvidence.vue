<script setup lang="ts">
import { nextTick, ref } from 'vue'
import type { FactRecord } from '@/api/report'

defineProps<{ facts: FactRecord[] }>()

const expanded = ref(new Set<string>())
const highlighted = ref('')
const rowEls = new Map<string, HTMLElement>()

function setRowEl(key: string, el: unknown) {
  if (el instanceof HTMLElement) rowEls.set(key, el)
}

function toggle(key: string) {
  const next = new Set(expanded.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expanded.value = next
}

/** 供正文 [fact_xxx] 点击联动：展开并滚动高亮对应证据行。 */
function highlight(factKey: string) {
  const next = new Set(expanded.value)
  next.add(factKey)
  expanded.value = next
  highlighted.value = factKey
  nextTick(() => {
    rowEls.get(factKey)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}

defineExpose({ highlight })
</script>

<template>
  <section class="facts">
    <h2>
      取数证据（{{ facts.length }} 条事实）
      <span class="sub">每个数字都由程序取数或派生，点击行查看 SQL 与哈希</span>
    </h2>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>事实编号</th>
            <th>指标</th>
            <th>周期</th>
            <th>展示值</th>
            <th>类型</th>
            <th>质量</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="f in facts" :key="f.factKey">
            <tr
              :ref="(el) => setRowEl(f.factKey, el)"
              class="fact-row"
              :class="{ hl: highlighted === f.factKey }"
              @click="toggle(f.factKey)"
            >
              <td class="mono">{{ f.factKey }}</td>
              <td>
                {{ f.metricName ?? f.metricId
                }}<span v-if="f.metricVersion" class="tag">v{{ f.metricVersion }}</span>
              </td>
              <td>{{ f.periodLabel ?? '—' }}</td>
              <td class="num">{{ f.displayValue ?? f.value ?? '—' }}</td>
              <td>
                {{ f.factType === 'DERIVED' ? '程序派生' : '取数' }}
                <span v-if="f.derivedFrom" class="derived">← {{ f.derivedFrom }}</span>
              </td>
              <td>{{ f.qualityStatus ?? '—' }}</td>
            </tr>
            <tr v-if="expanded.has(f.factKey)" class="detail-row">
              <td colspan="6">
                <template v-if="f.sqlText">
                  <h4>执行 SQL（只读）</h4>
                  <pre>{{ f.sqlText }}</pre>
                  <p class="hashes">
                    sql_hash：<code>{{ f.sqlHash }}</code> ｜ result_hash：<code>{{
                      f.resultHash
                    }}</code>
                  </p>
                </template>
                <p v-else class="hashes">程序派生事实（无 SQL），来源：{{ f.derivedFrom ?? '—' }}</p>
                <template v-if="f.specJson">
                  <h4>取数规格（MetricQuerySpec）</h4>
                  <pre>{{ f.specJson }}</pre>
                </template>
                <p v-if="f.qualityNote" class="hashes">质量备注：{{ f.qualityNote }}</p>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.facts h2 {
  font-size: 16px;
  font-weight: 600;
  color: var(--tb-blue-900);
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.sub {
  font-size: 12.5px;
  font-weight: 400;
  color: var(--tb-text-3);
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
  white-space: nowrap;
}

.fact-row {
  cursor: pointer;
}

.fact-row:hover {
  background: var(--tb-bg);
}

.fact-row.hl {
  background: var(--tb-amber-bg);
}

.mono {
  font-family: var(--tb-mono);
  font-size: 12px;
  color: var(--tb-blue-600);
  white-space: nowrap;
}

.num {
  white-space: nowrap;
}

.tag {
  margin-left: 6px;
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 11px;
  color: var(--tb-blue-600);
  background: var(--tb-blue-50);
}

.derived {
  font-size: 11.5px;
  color: var(--tb-text-3);
  font-family: var(--tb-mono);
}

.detail-row td {
  background: var(--tb-bg);
}

.detail-row h4 {
  margin: 8px 0 4px;
  font-size: 12.5px;
  color: var(--tb-text-2);
}

.detail-row pre {
  padding: 10px 12px;
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

.hashes {
  margin: 6px 0;
  font-size: 12px;
  color: var(--tb-text-2);
  word-break: break-all;
}

.hashes code {
  font-family: var(--tb-mono);
  font-size: 11.5px;
}
</style>
