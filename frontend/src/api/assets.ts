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
  chapters?: Array<{
    chapterId: string
    title: string
    metricIds?: string[]
    comparison?: boolean
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
