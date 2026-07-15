import type { Phase, RunStatus } from '@/api/report'

/** 运行状态 → 业务化文案 + 徽标样式类。 */
export const STATUS_META: Record<RunStatus, { label: string; cls: string }> = {
  AWAITING_OUTLINE_APPROVAL: { label: '待确认口径', cls: 'badge-await' },
  RUNNING: { label: '生成中', cls: 'badge-run' },
  BLOCKED: { label: '需人工处理', cls: 'badge-block' },
  AWAITING_PUBLISH_APPROVAL: { label: '待签发', cls: 'badge-await' },
  PUBLISHED: { label: '已签发', cls: 'badge-pub' },
  REJECTED: { label: '已驳回', cls: 'badge-rej' },
}

export function statusMeta(status: RunStatus | string): { label: string; cls: string } {
  return STATUS_META[status as RunStatus] ?? { label: String(status), cls: 'badge-rej' }
}

/** 流水线六步的展示顺序与业务名。 */
export const PHASES: Array<{ key: Phase; label: string }> = [
  { key: 'OUTLINE', label: '① 大纲' },
  { key: 'SPEC', label: '② 语义解析' },
  { key: 'FETCH', label: '③ 安全取数' },
  { key: 'FACT', label: '④ 事实构建' },
  { key: 'WRITE', label: '⑤ 章节撰写' },
  { key: 'AUDIT', label: '⑥ 证据审计' },
]

export function fmtTime(s: string | null | undefined): string {
  return s ? s.replace('T', ' ').slice(0, 16) : '—'
}
