<script setup lang="ts">
import { computed } from 'vue'
import { parseReportMd } from '@/utils/reportMd'

const props = defineProps<{ md: string }>()
const emit = defineEmits<{ 'fact-click': [factKey: string] }>()

const blocks = computed(() => parseReportMd(props.md))
</script>

<template>
  <article class="body">
    <template v-for="(b, bi) in blocks" :key="bi">
      <h2 v-if="b.t === 'h2'">
        <template v-for="(tk, ti) in b.inlines" :key="ti">
          <b v-if="tk.t === 'bold'">{{ tk.v }}</b>
          <button
            v-else-if="tk.t === 'factref'"
            class="factref"
            @click="emit('fact-click', tk.key)"
          >
            [{{ tk.key }}]
          </button>
          <template v-else>{{ tk.v }}</template>
        </template>
      </h2>

      <p v-else-if="b.t === 'p'">
        <template v-for="(tk, ti) in b.inlines" :key="ti">
          <b v-if="tk.t === 'bold'">{{ tk.v }}</b>
          <button
            v-else-if="tk.t === 'factref'"
            class="factref"
            @click="emit('fact-click', tk.key)"
          >
            [{{ tk.key }}]
          </button>
          <template v-else>{{ tk.v }}</template>
        </template>
      </p>

      <ul v-else-if="b.t === 'ul'">
        <li v-for="(item, ii) in b.items" :key="ii">
          <template v-for="(tk, ti) in item" :key="ti">
            <b v-if="tk.t === 'bold'">{{ tk.v }}</b>
            <button
              v-else-if="tk.t === 'factref'"
              class="factref"
              @click="emit('fact-click', tk.key)"
            >
              [{{ tk.key }}]
            </button>
            <template v-else>{{ tk.v }}</template>
          </template>
        </li>
      </ul>

      <div v-else-if="b.t === 'table'" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th v-for="(cell, ci) in b.header" :key="ci">
                <template v-for="(tk, ti) in cell" :key="ti">
                  <b v-if="tk.t === 'bold'">{{ tk.v }}</b>
                  <button
                    v-else-if="tk.t === 'factref'"
                    class="factref"
                    @click="emit('fact-click', tk.key)"
                  >
                    [{{ tk.key }}]
                  </button>
                  <template v-else>{{ tk.v }}</template>
                </template>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, ri) in b.rows" :key="ri">
              <td v-for="(cell, ci) in row" :key="ci">
                <template v-for="(tk, ti) in cell" :key="ti">
                  <b v-if="tk.t === 'bold'">{{ tk.v }}</b>
                  <button
                    v-else-if="tk.t === 'factref'"
                    class="factref"
                    @click="emit('fact-click', tk.key)"
                  >
                    [{{ tk.key }}]
                  </button>
                  <template v-else>{{ tk.v }}</template>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </article>
</template>

<style scoped>
.body {
  margin-top: 14px;
  font-size: 14px;
  line-height: 1.85;
}

.body h2 {
  margin: 18px 0 6px;
  font-size: 15.5px;
  font-weight: 700;
  color: var(--tb-blue-900);
}

.body p {
  margin: 6px 0;
}

.body ul {
  margin: 6px 0;
  padding-left: 22px;
}

.factref {
  border: none;
  padding: 0 3px;
  margin: 0 1px;
  border-radius: 4px;
  background: var(--tb-blue-50);
  color: var(--tb-blue-600);
  font-family: var(--tb-mono);
  font-size: 11px;
  cursor: pointer;
  vertical-align: baseline;
}

.factref:hover {
  background: var(--tb-blue-100);
}

.table-wrap {
  margin: 10px 0;
  overflow-x: auto;
  border: 1px solid var(--tb-border);
  border-radius: 8px;
}

.body table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.body th,
.body td {
  padding: 7px 12px;
  text-align: left;
  border-bottom: 1px solid var(--tb-border);
  white-space: nowrap;
}

.body th {
  background: var(--tb-blue-50);
  color: var(--tb-blue-900);
  font-weight: 600;
}

.body tbody tr:last-child td {
  border-bottom: none;
}
</style>
