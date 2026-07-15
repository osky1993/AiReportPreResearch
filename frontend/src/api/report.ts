/**
 * 智能报告流水线 API 客户端。
 * 契约对应后端 ReportController（前缀 /api/report）：所有动作端点均返回 RunDetail，
 * 动作后用返回值原地替换视图数据即可完成状态迁移；错误统一 400 {error}。
 */

export type RunStatus =
  | 'AWAITING_OUTLINE_APPROVAL'
  | 'RUNNING'
  | 'BLOCKED'
  | 'AWAITING_PUBLISH_APPROVAL'
  | 'PUBLISHED'
  | 'REJECTED'

export type Phase = 'OUTLINE' | 'SPEC' | 'FETCH' | 'FACT' | 'WRITE' | 'AUDIT'

export interface ReportRun {
  runId: number
  requestText: string
  templateId: string | null
  templateVersion: number | null
  metricVersionsJson: string | null
  periodLabel: string | null
  periodStart: string | null
  periodEnd: string | null
  compareStart: string | null
  compareEnd: string | null
  yoyStart: string | null
  yoyEnd: string | null
  status: RunStatus
  phase: Phase | null
  outlineJson: string | null
  chartsJson: string | null
  reportMd: string | null
  auditJson: string | null
  blockedReason: string | null
  outlineApprovedBy: string | null
  outlineApprovedAt: string | null
  publishApprovedBy: string | null
  publishApprovedAt: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface ReportStep {
  stepId: number
  runId: number
  phase: Phase
  attempt: number
  status: string
  inputJson: string | null
  outputJson: string | null
  errorText: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface FactRecord {
  factKey: string
  metricId: string
  metricVersion: number | null
  metricName: string | null
  chapterId: string | null
  factType: 'BASE' | 'DERIVED'
  value: number | null
  unit: string | null
  displayValue: string | null
  periodLabel: string | null
  dimensions: Record<string, unknown> | null
  specJson: string | null
  sqlText: string | null
  sqlHash: string | null
  resultHash: string | null
  derivedFrom: string | null
  qualityStatus: string | null
  qualityNote: string | null
}

export type AttributionLevel = 'observed' | 'associated' | 'hypothesis' | 'confirmed'

export interface ClaimRecord {
  claimId: number
  anomalyFactKey: string | null
  attributionLevel: AttributionLevel
  evidenceRefs: string[] | null
  narrative: string | null
  confirmedBy: string | null
  confirmedAt: string | null
}

export interface RunDetail {
  run: ReportRun
  steps: ReportStep[]
  facts: FactRecord[]
  claims: ClaimRecord[]
}

/** 大纲（run.outlineJson 二次解析所得）。 */
export interface OutlineChapter {
  chapterId: string
  title: string
  metricIds: string[]
  comparison?: boolean
  comparisons?: string[]
  guidance?: string
  stylePrompt?: string
  charts?: unknown[]
}

export interface Outline {
  templateId: string
  periodLabel: string
  chapters: OutlineChapter[]
  unresolved?: string[]
}

/** 审计包（run.auditJson 二次解析所得；落库版是 AuditResult 超集，含 chartChecks）。 */
export interface NumberCheck {
  displayText: string
  factKey: string
  expected: string
  parsed: string
  ok: boolean
}

export interface AuditResult {
  passed: boolean
  totalNumbers: number
  matchedNumbers: number
  rewriteRounds: number
  violations?: string[]
  details?: NumberCheck[]
  chartChecks?: Array<{ ok: boolean; [k: string]: unknown }>
}

export interface MetricSummary {
  metricId: string
  name: string
  latestVersion: number
  publishedVersion: number | null
  latestStatus: string
  source: string
  updatedAt: string | null
}

async function http<T>(path: string, opts?: RequestInit): Promise<T> {
  const res = await fetch(
    '/api/report' + path,
    opts ? { headers: { 'Content-Type': 'application/json' }, ...opts } : undefined,
  )
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string }
    throw new Error(body.error || `服务请求失败（HTTP ${res.status}）`)
  }
  return res.json() as Promise<T>
}

function post<T>(path: string, body: unknown): Promise<T> {
  return http<T>(path, { method: 'POST', body: JSON.stringify(body) })
}

// ---- 流水线 ----

/** 发起报告：同步执行①大纲生成（LLM 调用，约 5~20 秒）。 */
export function createRun(requestText: string): Promise<RunDetail> {
  return post('/runs', { requestText })
}

export function listRuns(): Promise<ReportRun[]> {
  return http('/runs')
}

export function getRun(runId: number | string): Promise<RunDetail> {
  return http(`/runs/${runId}`)
}

/** 卡点1确认：回传（可经人工勾选调整的）整棵大纲，口径就此锁死。 */
export function approveOutline(
  runId: number,
  approver: string,
  outline: Outline,
): Promise<RunDetail> {
  return post(`/runs/${runId}/outline/approve`, { approver, outline })
}

/** 卡点1/BLOCKED 打回：带修订意见重跑①。 */
export function regenerateOutline(runId: number, revisedRequest: string): Promise<RunDetail> {
  return post(`/runs/${runId}/outline/regenerate`, { revisedRequest })
}

/** 卡点2审批发布（服务端复核一致率 100% 才放行）。 */
export function approvePublish(runId: number, approver: string): Promise<RunDetail> {
  return post(`/runs/${runId}/publish/approve`, { approver })
}

export function rejectPublish(runId: number, approver: string, reason: string): Promise<RunDetail> {
  return post(`/runs/${runId}/publish/reject`, { approver, reason })
}

/** 归因人工确认（hypothesis→confirmed；待签发与已签发均可）。 */
export function confirmClaim(runId: number, claimId: number, approver: string): Promise<RunDetail> {
  return post(`/runs/${runId}/claims/${claimId}/confirm`, { approver })
}

/** BLOCKED / 停摆 RUNNING 断点续跑。 */
export function resumeRun(runId: number): Promise<RunDetail> {
  return post(`/runs/${runId}/resume`, {})
}

/** 指标摘要列表（供大纲里 metricId → 业务名称映射）。 */
export function listMetrics(): Promise<MetricSummary[]> {
  return http('/metrics')
}

// ---- 二次解析辅助（失败降级为 null，由视图容错呈现原文） ----

export function parseOutline(run: ReportRun): Outline | null {
  if (!run.outlineJson) return null
  try {
    return JSON.parse(run.outlineJson) as Outline
  } catch {
    return null
  }
}

export function parseAudit(run: ReportRun): AuditResult | null {
  if (!run.auditJson) return null
  try {
    return JSON.parse(run.auditJson) as AuditResult
  } catch {
    return null
  }
}

// ---- 发起后免二次 GET 的一次性详情缓存（createRun 响应即完整 RunDetail） ----

let primed: RunDetail | null = null

export function primeRunDetail(detail: RunDetail) {
  primed = detail
}

/** 取出并清空缓存；runId 不匹配则忽略。 */
export function takePrimedRunDetail(runId: number | string): RunDetail | null {
  const d = primed
  primed = null
  return d && String(d.run.runId) === String(runId) ? d : null
}
