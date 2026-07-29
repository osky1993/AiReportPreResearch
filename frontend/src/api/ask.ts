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
  /** 命中口径的中文描述（核验采纳时 AI 反翻译固化；旧资产/生成失败为 null） */
  matchedDescription: string | null
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

/** 口径说明（后端 MqlExplainService.Explanation，AI 反翻译 MQL）。 */
export interface MqlExplanation {
  explanation: string
  caveats: string[] | null
}

/**
 * 口径说明：把结果里的 MQL 反翻译为业务人员可读的中文口径描述（即席语境，展示层辅助）。
 * 服务端业务性拒绝返回 400 {error}，这里解出人话报错。
 */
export async function explainMql(mql: Record<string, unknown>): Promise<MqlExplanation> {
  const res = await fetch('/api/explain', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mql }),
  })
  if (!res.ok) {
    let msg = `服务请求失败（HTTP ${res.status}）`
    try {
      const body = (await res.json()) as { error?: string }
      if (body.error) msg = body.error
    } catch {
      /* 非 JSON 错误体，保留默认文案 */
    }
    throw new Error(msg)
  }
  return res.json() as Promise<MqlExplanation>
}

/** 核验闸门返回（后端 AssistedQueryService.VerifyResult）。 */
export interface VerifyResult {
  precipitated: boolean
  assetId: number | null
  message: string
  /** 随资产固化的口径描述（生成失败为 null，不影响沉淀） */
  description: string | null
}

/**
 * 核验闸门：采纳（accept=true，沉淀为口径资产，服务端重校验 MQL）或驳回（丢弃）。
 */
export function verify(
  question: string,
  mql: Record<string, unknown>,
  accept: boolean,
  createdBy: string,
): Promise<VerifyResult> {
  return post('/api/verify', { question, mql, accept, createdBy })
}
