/**
 * 智能问数 API 客户端。
 * 契约对应后端 QueryController：POST /api/ask、POST /api/reuse（AssistedResponse 信封）。
 */

/** 一次 NL→MQL→SQL→结果 的完整产物（后端 NlQueryResult）。 */
export interface NlQueryResult {
  question: string
  mql: Record<string, unknown> | null
  sql: string | null
  rows: Array<Record<string, unknown>> | null
  errors: string[] | null
  fixRounds: number
  success: boolean
  warnings: string[] | null
  clarifyReason: string | null
}

export type QueryState = 'HIT' | 'GENERATED' | 'CLARIFY' | 'FAILED'

/** 人在回路信封（后端 AssistedResponse）。 */
export interface AssistedResponse {
  state: QueryState
  source: 'CALIBER' | 'LLM' | 'NONE'
  confidence: number
  assetId: number | null
  matchedQuestion: string | null
  clarifyPrompt: string | null
  trace: NlQueryResult | null
}

async function post<T>(url: string, body: unknown): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) throw new Error(`服务请求失败（HTTP ${res.status}）`)
  return res.json() as Promise<T>
}

/**
 * 提问：先召回已核验口径（命中直取 / 候选澄清 / 未命中生成）。
 * bypassCaliber=true 时跳过口径召回，直接由模型按原问题生成（澄清后「不复用口径」分支）。
 */
export function ask(question: string, bypassCaliber = false): Promise<AssistedResponse> {
  return post('/api/ask', { question, bypassCaliber })
}

/** 候选口径澄清后确认复用：按 assetId 直取执行。 */
export function reuse(assetId: number, question: string): Promise<AssistedResponse> {
  return post('/api/reuse', { assetId, question })
}
