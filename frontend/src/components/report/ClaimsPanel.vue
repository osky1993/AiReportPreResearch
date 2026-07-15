<script setup lang="ts">
import type { AttributionLevel, ClaimRecord } from '@/api/report'

defineProps<{
  claims: ClaimRecord[]
  /** 待签发与已签发状态可做人工确认 */
  canConfirm: boolean
  busy: boolean
}>()

const emit = defineEmits<{
  confirm: [claimId: number]
  'fact-click': [factKey: string]
}>()

const LEVEL_META: Record<AttributionLevel, { label: string; cls: string }> = {
  observed: { label: '观察', cls: 'lv-observed' },
  associated: { label: '关联', cls: 'lv-associated' },
  hypothesis: { label: '假设·待验证', cls: 'lv-hypothesis' },
  confirmed: { label: '已确认', cls: 'lv-confirmed' },
}

function meta(level: AttributionLevel) {
  return LEVEL_META[level] ?? { label: level, cls: 'lv-observed' }
}

function fmtTime(s: string | null): string {
  return s ? s.replace('T', ' ').slice(0, 16) : ''
}
</script>

<template>
  <section class="claims">
    <h2>
      异动归因（{{ claims.length }} 条）
      <span class="sub">候选由程序给出、AI 只负责措辞；「已确认」仅可由人工勾选达成</span>
    </h2>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>异动事实</th>
            <th>结论强度</th>
            <th>归因叙述</th>
            <th>证据引用</th>
            <th>人工确认</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in claims" :key="c.claimId">
            <td>
              <button
                v-if="c.anomalyFactKey"
                class="factref"
                @click="emit('fact-click', c.anomalyFactKey)"
              >
                [{{ c.anomalyFactKey }}]
              </button>
              <span v-else>—</span>
            </td>
            <td>
              <span class="lv" :class="meta(c.attributionLevel).cls">{{
                meta(c.attributionLevel).label
              }}</span>
            </td>
            <td class="narrative">{{ c.narrative ?? '—' }}</td>
            <td>
              <template v-for="(ref, i) in c.evidenceRefs ?? []" :key="i">
                <span v-if="ref.startsWith('EVT-')" class="evt">{{ ref }}</span>
                <button v-else class="factref" @click="emit('fact-click', ref)">
                  [{{ ref }}]
                </button>
              </template>
            </td>
            <td>
              <button
                v-if="c.attributionLevel === 'hypothesis' && canConfirm"
                class="btn-confirm"
                :disabled="busy"
                @click="emit('confirm', c.claimId)"
              >
                ✓ 确认
              </button>
              <span v-else-if="c.confirmedBy" class="confirmed-by">
                {{ c.confirmedBy }}<br />{{ fmtTime(c.confirmedAt) }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.claims h2 {
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
  vertical-align: top;
}

th {
  background: var(--tb-blue-50);
  color: var(--tb-blue-900);
  font-weight: 600;
  white-space: nowrap;
}

tbody tr:last-child td {
  border-bottom: none;
}

.narrative {
  max-width: 420px;
}

.factref {
  border: none;
  padding: 0 3px;
  margin: 0 2px 2px 0;
  border-radius: 4px;
  background: var(--tb-blue-50);
  color: var(--tb-blue-600);
  font-family: var(--tb-mono);
  font-size: 11px;
  cursor: pointer;
}

.factref:hover {
  background: var(--tb-blue-100);
}

.evt {
  display: inline-block;
  margin: 0 2px 2px 0;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 11px;
  color: var(--tb-amber);
  background: var(--tb-amber-bg);
}

.lv {
  padding: 1px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.lv-observed {
  color: var(--tb-text-3);
  background: var(--tb-bg);
}

.lv-associated {
  color: var(--tb-blue-600);
  background: var(--tb-blue-50);
}

.lv-hypothesis {
  color: var(--tb-amber);
  background: var(--tb-amber-bg);
}

.lv-confirmed {
  color: var(--tb-green);
  background: var(--tb-green-bg);
}

.btn-confirm {
  padding: 3px 10px;
  border: 1px solid var(--tb-green);
  border-radius: 7px;
  background: var(--tb-surface);
  color: var(--tb-green);
  font-size: 12px;
  cursor: pointer;
}

.btn-confirm:hover:not(:disabled) {
  background: var(--tb-green-bg);
}

.btn-confirm:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.confirmed-by {
  font-size: 11.5px;
  color: var(--tb-text-3);
}
</style>
