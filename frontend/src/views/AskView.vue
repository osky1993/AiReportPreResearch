<script setup lang="ts">
import { nextTick, ref, useTemplateRef } from 'vue'
import { ask, explainMql, reuse, verify, type AssistedResponse, type MqlExplanation } from '@/api/ask'

interface ChatItem {
  id: number
  role: 'user' | 'assistant'
  /** user 消息的文本 */
  text?: string
  /** assistant 消息对应的原始提问（供复用口径时回传） */
  question?: string
  loading?: boolean
  resp?: AssistedResponse
  /** 网络/服务级错误（非业务 FAILED） */
  error?: string
  /** 口径操作已处理（CLARIFY 复用候选 / HIT 判定不符改走生成），隐藏对应操作按钮 */
  caliberResolved?: boolean
  /** 核验人姓名（GENERATED 卡片核验区输入） */
  verifier?: string
  verifyPending?: boolean
  /** 核验结论文案；非空即隐藏核验按钮 */
  verifyMsg?: string
  verifyOk?: boolean
  /** 采纳时随资产固化的口径描述（服务端生成，可空） */
  verifyDesc?: string
  /** 口径说明（AI 反翻译 MQL）：加载态 / 结果 / 失败文案 */
  explainPending?: boolean
  explanation?: MqlExplanation
  explainError?: string
}

const EXAMPLES = [
  '2026年6月30日库存余额最高的5个县级国库，列出国库简称和余额',
  '2026年6月全省国库借方发生额合计是多少',
  '全省共有多少家区县支库',
]

const items = ref<ChatItem[]>([])
const input = ref('')
const busy = ref(false)
const listRef = useTemplateRef<HTMLElement>('listRef')
let nextId = 1

function scrollToBottom() {
  nextTick(() => {
    listRef.value?.scrollTo({ top: listRef.value.scrollHeight, behavior: 'smooth' })
  })
}

async function runRequest(question: string, call: () => Promise<AssistedResponse>) {
  const pending: ChatItem = { id: nextId++, role: 'assistant', question, loading: true }
  items.value.push(pending)
  busy.value = true
  scrollToBottom()
  try {
    pending.resp = await call()
  } catch (e) {
    pending.error = e instanceof Error ? e.message : '请求失败，请稍后重试'
  } finally {
    pending.loading = false
    busy.value = false
    scrollToBottom()
  }
}

function send(text?: string) {
  const question = (text ?? input.value).trim()
  if (!question || busy.value) return
  input.value = ''
  items.value.push({ id: nextId++, role: 'user', text: question })
  runRequest(question, () => ask(question))
}

function reuseCaliber(item: ChatItem) {
  if (!item.resp?.assetId || !item.question || busy.value) return
  item.caliberResolved = true
  runRequest(item.question, () => reuse(item.resp!.assetId!, item.question!))
}

/** 澄清后不复用候选口径，或 HIT 后人工判定命中口径不符：跳过口径召回，让模型按原问题直接生成。 */
function generateDirectly(item: ChatItem) {
  if (!item.question || busy.value) return
  item.caliberResolved = true
  runRequest(item.question, () => ask(item.question!, true))
}

/** 核验闸门：采纳沉淀为口径资产 / 驳回丢弃。 */
async function doVerify(item: ChatItem, accept: boolean) {
  const mql = item.resp?.trace?.mql
  if (!mql || !item.question || item.verifyPending) return
  item.verifyPending = true
  try {
    const r = await verify(item.question, mql, accept, (item.verifier ?? '').trim() || '业务用户')
    item.verifyOk = r.precipitated
    item.verifyMsg = r.message
    item.verifyDesc = r.description ?? undefined
  } catch (e) {
    item.verifyOk = false
    item.verifyMsg = e instanceof Error ? e.message : '核验请求失败，请稍后重试'
  } finally {
    item.verifyPending = false
  }
}

/** 口径说明按需加载：另一次 LLM 调用，不阻塞出数主链路；失败只影响说明区。 */
async function loadExplanation(item: ChatItem) {
  const mql = item.resp?.trace?.mql
  if (!mql || item.explainPending || item.explanation) return
  item.explainPending = true
  item.explainError = undefined
  try {
    item.explanation = await explainMql(mql)
  } catch (e) {
    item.explainError = e instanceof Error ? e.message : '口径说明生成失败，请稍后重试'
  } finally {
    item.explainPending = false
  }
}

function stateBadge(resp: AssistedResponse): { label: string; cls: string } {
  switch (resp.state) {
    case 'HIT':
      return { label: '口径命中', cls: 'badge-hit' }
    case 'GENERATED':
      return { label: 'AI 生成', cls: 'badge-gen' }
    case 'CLARIFY':
      return { label: '需澄清', cls: 'badge-clarify' }
    case 'FAILED':
      return { label: '查询失败', cls: 'badge-fail' }
  }
}

function columns(rows: Array<Record<string, unknown>>): string[] {
  return rows.length ? Object.keys(rows[0]!) : []
}

function fmt(v: unknown): string {
  if (v == null) return '—'
  if (typeof v === 'number') return v.toLocaleString('zh-CN', { maximumFractionDigits: 4 })
  return String(v)
}

function pct(v: number): string {
  return `${(v * 100).toFixed(1)}%`
}
</script>

<template>
  <main class="ask">
    <div ref="listRef" class="chat">
      <!-- 空状态：引导 + 示例问题 -->
      <div v-if="items.length === 0" class="empty">
        <h1>智能问数</h1>
        <p class="empty-sub">
          用一句话查询国库库存、机构数据。已核验口径直接复用，全程可查看取数依据（金额单位：亿元）。
        </p>
        <div class="examples">
          <button v-for="q in EXAMPLES" :key="q" class="example" @click="send(q)">
            {{ q }}
          </button>
        </div>
      </div>

      <template v-for="item in items" :key="item.id">
        <!-- 用户提问 -->
        <div v-if="item.role === 'user'" class="msg-user">{{ item.text }}</div>

        <!-- 回答卡片 -->
        <div v-else class="msg-answer">
          <div v-if="item.loading" class="answer-loading">
            <span class="dot"></span><span class="dot"></span><span class="dot"></span>
            正在为您取数…
          </div>

          <div v-else-if="item.error" class="answer-error">{{ item.error }}</div>

          <template v-else-if="item.resp">
            <div class="answer-head">
              <span class="badge" :class="stateBadge(item.resp).cls">{{
                stateBadge(item.resp).label
              }}</span>
              <span v-if="item.resp.state === 'HIT'" class="head-note">
                已复用业务核验口径，确定性取数，未调用大模型
              </span>
              <span v-else-if="item.resp.state === 'GENERATED'" class="head-note">
                本次取数逻辑由 AI 新生成，口径未经人工核验，请对照取数依据确认
              </span>
            </div>

            <!-- 命中口径的溯源信息 + 误命中申诉出口 -->
            <div v-if="item.resp.state === 'HIT' && item.resp.matchedQuestion" class="matched">
              <span
                >命中口径「{{ item.resp.matchedQuestion }}」（相似度
                {{ pct(item.resp.confidence) }}）</span
              >
              <button
                v-if="!item.caliberResolved"
                class="btn-secondary"
                :disabled="busy"
                @click="generateDirectly(item)"
              >
                口径不符？AI 按原问题重新生成
              </button>
              <span v-else class="mismatch-done">已改按原问题重新生成，结果见下方</span>
            </div>

            <!-- 需澄清：候选口径确认 或 补充问题 -->
            <div v-if="item.resp.state === 'CLARIFY'" class="clarify">
              <p>{{ item.resp.clarifyPrompt || item.resp.trace?.clarifyReason || '请补充更具体的条件后重新提问。' }}</p>
              <div v-if="item.resp.assetId && item.resp.matchedQuestion" class="clarify-candidate">
                <span
                  >相近的已核验口径：「{{ item.resp.matchedQuestion }}」（相似度
                  {{ pct(item.resp.confidence) }}）</span
                >
                <span v-if="item.resp.matchedDescription" class="candidate-desc">
                  该口径的取数说明：{{ item.resp.matchedDescription }}
                </span>
                <span v-if="!item.caliberResolved" class="clarify-actions">
                  <button class="btn-primary" :disabled="busy" @click="reuseCaliber(item)">
                    按该口径查询
                  </button>
                  <button class="btn-secondary" :disabled="busy" @click="generateDirectly(item)">
                    不复用，AI 按原问题生成
                  </button>
                </span>
              </div>
            </div>

            <!-- 失败原因 -->
            <div v-if="item.resp.state === 'FAILED'" class="fail">
              <p>本次查询未能完成，请调整问法重试或联系管理员。</p>
              <ul v-if="item.resp.trace?.errors?.length">
                <li v-for="(err, i) in item.resp.trace.errors" :key="i">{{ err }}</li>
              </ul>
            </div>

            <!-- 结果表 -->
            <template v-if="item.resp.trace?.success && item.resp.trace.rows">
              <p v-if="item.resp.trace.rows.length === 0" class="no-rows">
                查询执行成功，但没有符合条件的数据。
              </p>
              <div v-else class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th v-for="c in columns(item.resp.trace.rows)" :key="c">{{ c }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, ri) in item.resp.trace.rows" :key="ri">
                      <td v-for="c in columns(item.resp.trace.rows)" :key="c">
                        {{ fmt(row[c]) }}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>

            <ul v-if="item.resp.trace?.warnings?.length" class="warnings">
              <li v-for="(w, i) in item.resp.trace.warnings" :key="i">⚠ {{ w }}</li>
            </ul>

            <!-- 口径说明：命中口径直接展示核验时固化的描述；否则按需 AI 反翻译（展示层辅助） -->
            <div v-if="item.resp.trace?.success && item.resp.trace.mql" class="explain">
              <div v-if="item.resp.matchedDescription" class="explain-body">
                <div class="explain-title">取数口径说明（已随核验固化）</div>
                <p class="explain-text">{{ item.resp.matchedDescription }}</p>
                <p class="explain-note">
                  本说明在该口径人工核验采纳时生成并随资产固化；数字以上方查询结果为准。
                </p>
              </div>
              <button
                v-else-if="!item.explanation && !item.explainPending && !item.explainError"
                class="btn-secondary"
                @click="loadExplanation(item)"
              >
                📖 生成口径说明
              </button>
              <span v-if="item.explainPending" class="explain-loading">
                <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                正在解读取数口径…
              </span>
              <div v-if="item.explainError" class="explain-error">
                口径说明生成失败：{{ item.explainError }}
                <button class="btn-secondary" @click="loadExplanation(item)">重试</button>
              </div>
              <div v-if="item.explanation" class="explain-body">
                <div class="explain-title">取数口径说明</div>
                <p class="explain-text">{{ item.explanation.explanation }}</p>
                <ul v-if="item.explanation.caveats?.length" class="explain-caveats">
                  <li v-for="(c, i) in item.explanation.caveats" :key="i">待确认：{{ c }}</li>
                </ul>
                <p class="explain-note">
                  本说明由 AI 根据取数结构生成，供理解口径参考；数字以上方查询结果为准。
                </p>
              </div>
            </div>

            <!-- 核验闸门：AI 生成的结果可采纳沉淀为口径资产，或驳回丢弃 -->
            <div
              v-if="item.resp.state === 'GENERATED' && item.resp.trace?.success && item.resp.trace.mql"
              class="verify"
            >
              <template v-if="!item.verifyMsg">
                <span class="verify-hint">结果与口径无误？核验后沉淀为口径资产，同类问题即可直接复用：</span>
                <span class="verify-actions">
                  <input
                    v-model="item.verifier"
                    class="verify-name"
                    placeholder="核验人（默认：业务用户）"
                  />
                  <button
                    class="btn-primary"
                    :disabled="item.verifyPending"
                    @click="doVerify(item, true)"
                  >
                    {{ item.verifyPending ? '处理中…' : '核验采纳' }}
                  </button>
                  <button
                    class="btn-secondary"
                    :disabled="item.verifyPending"
                    @click="doVerify(item, false)"
                  >
                    驳回
                  </button>
                </span>
              </template>
              <div v-else class="verify-done">
                <span class="verify-result" :class="item.verifyOk ? 'ok' : 'no'">
                  {{ item.verifyOk ? '✓' : '—' }} {{ item.verifyMsg }}
                </span>
                <span v-if="item.verifyOk && item.verifyDesc" class="verify-desc">
                  已固化口径说明：{{ item.verifyDesc }}
                </span>
              </div>
            </div>

            <!-- 取数依据钻取 -->
            <details v-if="item.resp.trace?.sql" class="evidence">
              <summary>查看取数依据</summary>
              <div class="evidence-body">
                <h4>执行 SQL（只读）</h4>
                <pre>{{ item.resp.trace.sql }}</pre>
                <h4>结构化查询（MQL）</h4>
                <pre>{{ JSON.stringify(item.resp.trace.mql, null, 2) }}</pre>
              </div>
            </details>
          </template>
        </div>
      </template>
    </div>

    <!-- 输入区 -->
    <div class="composer">
      <textarea
        v-model="input"
        rows="1"
        placeholder="请输入您的问题，如：最近一天全省国库库存余额合计是多少"
        @keydown.enter.exact.prevent="send()"
      ></textarea>
      <button class="btn-primary btn-send" :disabled="busy || !input.trim()" @click="send()">
        {{ busy ? '查询中…' : '提问' }}
      </button>
    </div>
  </main>
</template>

<style scoped>
.ask {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.chat {
  flex: 1;
  overflow-y: auto;
  padding: 28px 0 16px;
}

.chat > * {
  max-width: 860px;
  margin-left: auto;
  margin-right: auto;
}

/* ---- 空状态 ---- */
.empty {
  padding: 64px 24px 0;
  text-align: center;
}

.empty h1 {
  font-size: 26px;
  color: var(--tb-blue-900);
  font-weight: 700;
}

.empty-sub {
  margin-top: 10px;
  color: var(--tb-text-2);
}

.examples {
  margin-top: 28px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.example {
  padding: 9px 18px;
  border: 1px solid var(--tb-border);
  border-radius: 999px;
  background: var(--tb-surface);
  color: var(--tb-blue-700);
  font-size: 13.5px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.example:hover {
  border-color: var(--tb-blue-500);
  background: var(--tb-blue-50);
}

/* ---- 消息 ---- */
.msg-user {
  width: fit-content;
  max-width: 640px;
  margin-top: 20px;
  margin-right: 24px;
  margin-left: auto;
  padding: 9px 16px;
  border-radius: 14px 14px 3px 14px;
  background: var(--tb-blue-600);
  color: #fff;
}

.msg-answer {
  margin-top: 12px;
  padding: 16px 20px;
  margin-left: 24px;
  margin-right: 24px;
  background: var(--tb-surface);
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius);
  box-shadow: var(--tb-shadow);
}

@media (min-width: 940px) {
  .msg-user {
    margin-right: 0;
  }
  .msg-answer {
    margin-left: 0;
    margin-right: 0;
  }
}

.answer-loading {
  color: var(--tb-text-2);
}

.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 4px;
  border-radius: 50%;
  background: var(--tb-blue-500);
  animation: pulse 1.2s infinite ease-in-out;
}

.dot:nth-child(2) {
  animation-delay: 0.2s;
}

.dot:nth-child(3) {
  animation-delay: 0.4s;
  margin-right: 10px;
}

@keyframes pulse {
  0%,
  80%,
  100% {
    opacity: 0.25;
  }
  40% {
    opacity: 1;
  }
}

.answer-error {
  color: var(--tb-red);
}

.answer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.badge {
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}

.badge-hit {
  color: var(--tb-green);
  background: var(--tb-green-bg);
}

.badge-gen {
  color: var(--tb-blue-600);
  background: var(--tb-blue-50);
}

.badge-clarify {
  color: var(--tb-amber);
  background: var(--tb-amber-bg);
}

.badge-fail {
  color: var(--tb-red);
  background: var(--tb-red-bg);
}

.head-note {
  font-size: 12.5px;
  color: var(--tb-text-3);
}

.matched {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--tb-text-2);
}

.mismatch-done {
  font-size: 12.5px;
  color: var(--tb-text-3);
}

.clarify {
  margin-top: 10px;
  color: var(--tb-text);
}

.clarify-candidate {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 14px;
  background: var(--tb-blue-50);
  border-radius: 8px;
  font-size: 13px;
  color: var(--tb-text-2);
}

.candidate-desc {
  flex-basis: 100%;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--tb-text);
}

.fail {
  margin-top: 10px;
  color: var(--tb-red);
  font-size: 13.5px;
}

.fail ul {
  margin-top: 6px;
  padding-left: 18px;
}

.no-rows {
  margin-top: 12px;
  color: var(--tb-text-2);
}

/* ---- 结果表 ---- */
.table-wrap {
  margin-top: 14px;
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
  padding: 8px 14px;
  text-align: left;
  border-bottom: 1px solid var(--tb-border);
}

th {
  background: var(--tb-blue-50);
  color: var(--tb-blue-900);
  font-weight: 600;
  position: sticky;
  top: 0;
}

tbody tr:last-child td {
  border-bottom: none;
}

tbody tr:hover {
  background: var(--tb-bg);
}

.warnings {
  margin-top: 10px;
  padding-left: 4px;
  list-style: none;
  color: var(--tb-amber);
  font-size: 12.5px;
}

/* ---- 口径说明 ---- */
.explain {
  margin-top: 12px;
}

.explain-loading {
  font-size: 13px;
  color: var(--tb-text-2);
}

.explain-error {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--tb-red);
}

.explain-body {
  padding: 12px 16px;
  background: var(--tb-bg);
  border: 1px solid var(--tb-border);
  border-left: 3px solid var(--tb-blue-500);
  border-radius: 8px;
}

.explain-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--tb-blue-900);
}

.explain-text {
  margin-top: 6px;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--tb-text);
}

.explain-caveats {
  margin-top: 8px;
  padding-left: 4px;
  list-style: none;
  font-size: 12.5px;
  color: var(--tb-amber);
}

.explain-note {
  margin-top: 8px;
  font-size: 12px;
  color: var(--tb-text-3);
}

/* ---- 核验闸门 ---- */
.verify {
  margin-top: 14px;
  padding: 10px 14px;
  background: var(--tb-blue-50);
  border-radius: 8px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.verify-hint {
  font-size: 13px;
  color: var(--tb-text-2);
}

.verify-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.verify-name {
  width: 180px;
  padding: 4px 10px;
  border: 1px solid var(--tb-border);
  border-radius: 7px;
  font-family: var(--tb-font);
  font-size: 13px;
  color: var(--tb-text);
  outline: none;
}

.verify-name:focus {
  border-color: var(--tb-blue-500);
}

.verify .btn-primary {
  padding: 5px 14px;
  font-size: 13px;
  border-radius: 7px;
}

.verify-done {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.verify-result {
  font-size: 13px;
}

.verify-desc {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--tb-text-2);
}

.verify-result.ok {
  color: var(--tb-green);
  font-weight: 500;
}

.verify-result.no {
  color: var(--tb-text-2);
}

/* ---- 取数依据 ---- */
.evidence {
  margin-top: 12px;
}

.evidence summary {
  cursor: pointer;
  color: var(--tb-blue-600);
  font-size: 13px;
  user-select: none;
}

.evidence-body {
  margin-top: 10px;
}

.evidence-body h4 {
  margin: 10px 0 4px;
  font-size: 12.5px;
  color: var(--tb-text-2);
  font-weight: 600;
}

.evidence-body pre {
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

/* ---- 输入区 ---- */
.composer {
  flex: none;
  display: flex;
  gap: 10px;
  align-items: flex-end;
  max-width: 860px;
  width: 100%;
  margin: 0 auto;
  padding: 12px 24px 20px;
}

@media (min-width: 940px) {
  .composer {
    padding-left: 0;
    padding-right: 0;
  }
}

textarea {
  flex: 1;
  resize: none;
  padding: 11px 16px;
  border: 1px solid var(--tb-border);
  border-radius: var(--tb-radius);
  background: var(--tb-surface);
  font-family: var(--tb-font);
  font-size: 14px;
  line-height: 1.5;
  color: var(--tb-text);
  outline: none;
  box-shadow: var(--tb-shadow);
}

textarea:focus {
  border-color: var(--tb-blue-500);
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
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-send {
  flex: none;
}

.clarify-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.clarify-candidate .btn-primary,
.btn-secondary {
  padding: 5px 14px;
  font-size: 13px;
  border-radius: 7px;
}

.btn-secondary {
  border: 1px solid var(--tb-blue-500);
  background: var(--tb-surface);
  color: var(--tb-blue-600);
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-secondary:hover:not(:disabled) {
  background: var(--tb-blue-50);
}

.btn-secondary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
