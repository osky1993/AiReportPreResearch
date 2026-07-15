/**
 * 资产治理 API 客户端：报告模板 / 指标口径 / 问数沉淀口径 三域。
 * 模板与指标为多版本行不可变模型（DRAFT→PUBLISHED→DEPRECATED）；
 * caliber 为单行模型（ACTIVE/DEPRECATED），治理端点在报告层 CaliberBridgeController。
 * 管理域错误结构：400 {error, details?:[{location,message}]}。
 */

export interface ValidationDetail {
  location: string
  message: string
}

export class AssetApiError extends Error {
  details: ValidationDetail[]
  constructor(message: string, details: ValidationDetail[] = []) {
    super(message)
    this.details = details
  }
}

async function http<T>(path: string, opts?: RequestInit): Promise<T> {
  const res = await fetch(
    '/api/report' + path,
    opts ? { headers: { 'Content-Type': 'application/json' }, ...opts } : undefined,
  )
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as {
      error?: string
      details?: ValidationDetail[]
    }
    throw new AssetApiError(body.error || `服务请求失败（HTTP ${res.status}）`, body.details ?? [])
  }
  return res.json() as Promise<T>
}

// ---- 通用版本模型 ----

export type AssetStatus = 'DRAFT' | 'PUBLISHED' | 'DEPRECATED'

export interface VersionInfo {
  version: number
  name: string
  status: AssetStatus
  source: string
  createdBy: string | null
  createdAt: string | null
  remark: string | null
}

// ---- 报告模板 ----

export interface TemplateSummary {
  templateId: string
  name: string
  latestVersion: number
  publishedVersion: number | null
  latestStatus: AssetStatus
  source: string
  updatedAt: string | null
}

/** 模板定义体（业务渲染取已知字段，整体以 JSON 折叠兜底）。 */
export interface TemplateBody {
  templateId: string
  name: string
  keywords?: string[]
  periodTypes?: string[]
  /** 注意：模板定义的章节指标字段是 metrics（运行期 Outline 才叫 metricIds） */
  chapters?: Array<{
    chapterId: string
    title: string
    metrics?: string[]
    comparison?: string
    comparisons?: string[]
    guidance?: string
    stylePrompt?: string
    [k: string]: unknown
  }>
  [k: string]: unknown
}

export interface TemplateDetail {
  templateId: string
  versions: VersionInfo[]
  published: TemplateBody | null
  latest: TemplateBody | null
}

export function listTemplates(): Promise<TemplateSummary[]> {
  return http('/templates')
}

export function getTemplate(id: string): Promise<TemplateDetail> {
  return http(`/templates/${encodeURIComponent(id)}`)
}

export function getTemplateVersion(
  id: string,
  version: number,
): Promise<{ version: number; status: AssetStatus; template: TemplateBody }> {
  return http(`/templates/${encodeURIComponent(id)}/versions/${version}`)
}

/** 发布/下架结果（后端 SaveResult 同构）。 */
export interface StatusResult {
  version: number
  status: AssetStatus
  [k: string]: unknown
}

function post<T>(path: string, body: unknown): Promise<T> {
  return http<T>(path, { method: 'POST', body: JSON.stringify(body) })
}

/** 发布模板指定版本（事务内旧发布版自动下架 + 资产热加载，自检失败即回滚）。 */
export function publishTemplate(id: string, version: number): Promise<StatusResult> {
  return post(`/templates/${encodeURIComponent(id)}/publish`, { version })
}

export function deprecateTemplate(id: string, version: number): Promise<StatusResult> {
  return post(`/templates/${encodeURIComponent(id)}/deprecate`, { version })
}

/** AI 起草结果：草案 + 未映射表述 + 说明（只从既有指标选，不落库）。 */
export interface DraftResult {
  draft: TemplateBody
  unresolved: string[]
  notes: string[]
}

export function draftTemplate(description: string, createdBy: string): Promise<DraftResult> {
  return post('/templates/draft', { description, createdBy })
}

/** 干跑校验（不落库）：返回 {valid} 或 {valid:false, errors:[{location,message}]}。 */
export function validateTemplate(
  template: TemplateBody,
): Promise<{ valid: boolean; errors?: ValidationDetail[] }> {
  return post('/templates/validate', { template })
}

export interface SaveResult {
  templateId?: string
  metricId?: string
  version: number
  status: AssetStatus
}

/** 新建模板（v1 DRAFT）。 */
export function createTemplate(
  template: TemplateBody,
  createdBy: string,
  remark: string,
): Promise<SaveResult> {
  return post('/templates', { template, createdBy, remark })
}

/** 已有模板存为新版本 DRAFT。 */
export function saveTemplateVersion(
  id: string,
  template: TemplateBody,
  createdBy: string,
  remark: string,
): Promise<SaveResult> {
  return http(`/templates/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify({ template, createdBy, remark }),
  })
}

// ---- 指标口径 ----

export interface MetricSummary {
  metricId: string
  name: string
  latestVersion: number
  publishedVersion: number | null
  latestStatus: AssetStatus
  source: string
  updatedAt: string | null
}

/** 指标定义体（业务渲染取已知字段，mqlTemplate 折叠展示）。 */
export interface MetricBody {
  metricId: string
  name: string
  unit?: string
  timeBound?: boolean
  comparable?: boolean
  valueColumn?: string
  nullPolicy?: string
  qualityChecks?: string[]
  mqlTemplate?: Record<string, unknown>
  [k: string]: unknown
}

export interface MetricDetail {
  metricId: string
  versions: VersionInfo[]
  published: MetricBody | null
  latest: MetricBody | null
}

export function listMetricAssets(): Promise<MetricSummary[]> {
  return http('/metrics')
}

export function getMetric(id: string): Promise<MetricDetail> {
  return http(`/metrics/${encodeURIComponent(id)}`)
}

export function publishMetric(id: string, version: number): Promise<StatusResult> {
  return post(`/metrics/${encodeURIComponent(id)}/publish`, { version })
}

export function deprecateMetric(id: string, version: number): Promise<StatusResult> {
  return post(`/metrics/${encodeURIComponent(id)}/deprecate`, { version })
}

/** 指标被哪些 PUBLISHED 模板引用（下架前保护检查）。 */
export function getMetricReferences(
  id: string,
): Promise<{ metricId: string; referencedBy: string[] }> {
  return http(`/metrics/${encodeURIComponent(id)}/references`)
}

// ---- 指标制作向导（试查 → 反翻译 → 参数化 → 保存） ----

export interface TryResult {
  mql: Record<string, unknown> | null
  sql: string | null
  rows: Array<Record<string, unknown>> | null
  columns: string[] | null
  success: boolean
  errors: string[] | null
  warnings: string[] | null
  clarifyReason: string | null
}

/** 试查：走底座自由生成链路（资产制作期工具，失败也返回 200 + success=false）。 */
export function tryMetric(question: string): Promise<TryResult> {
  return post('/metrics/try', { question })
}

/** 口径反翻译：把 MQL 译回业务话术供人工核对。 */
export function explainMql(
  mql: Record<string, unknown>,
): Promise<{ explanation: string; caveats: string[] }> {
  return post('/metrics/explain', { mql })
}

export interface ParamSuggestion {
  path: string
  field: string
  op: string
  value: string
  placeholder: string
  reason: string
}

/** 参数化扫描：列出可替换为 {{period_start}}/{{period_end}} 的日期字面量建议。 */
export function parameterizeScan(
  mql: Record<string, unknown>,
): Promise<{ suggestions: ParamSuggestion[]; notes: string[] }> {
  return post('/metrics/parameterize', { mql })
}

/** 参数化应用：按勾选的建议 path 替换占位符（服务端重算校验，防篡改任意节点）。 */
export function parameterizeApply(
  mql: Record<string, unknown>,
  apply: string[],
): Promise<{
  mqlTemplate: Record<string, unknown>
  applied: string[]
  remaining: ParamSuggestion[]
}> {
  return post('/metrics/parameterize', { mql, apply })
}

/** 指标定义体（保存用；派生/维度/异常规则等高级形态暂不在向导内编辑）。 */
export interface MetricSaveBody {
  metricId: string
  name: string
  unit: string
  timeBound: boolean
  comparable: boolean
  valueColumn: string
  nullPolicy: string
  qualityChecks: string[]
  mqlTemplate: Record<string, unknown>
}

/** 保存指标（服务端跑五类校验链：STRUCTURE/PLACEHOLDER/MQL_VALIDATION/TRIAL_EXECUTION/RESULT_SHAPE）。 */
export function createMetric(
  metric: MetricSaveBody,
  tryQuestion: string,
  createdBy: string,
): Promise<SaveResult> {
  return post('/metrics', { metric, tryQuestion, createdBy })
}

// ---- 问数沉淀口径（caliber） ----

export interface CaliberAsset {
  id: number
  question: string
  mqlJson: string
  createdBy: string | null
  createdAt: string | null
  status: 'ACTIVE' | 'DEPRECATED'
}

export function listCalibers(): Promise<CaliberAsset[]> {
  return http('/calibers?status=all')
}

export function getCaliber(id: number | string): Promise<CaliberAsset> {
  return http(`/calibers/${id}`)
}

/** 下架口径（服务端联动移出智能问数召回索引）。 */
export function deprecateCaliber(
  id: number,
  operator: string,
): Promise<{ id: number; status: string }> {
  return post(`/calibers/${id}/deprecate`, { operator })
}
