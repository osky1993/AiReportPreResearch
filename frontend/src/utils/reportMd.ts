/**
 * 报告终稿极简 markdown 的 token 化解析（不产 HTML、不用 v-html，由 ReportBody 模板渲染）。
 * 支持：`## `/`# ` 标题、`- ` 列表、`| a | b |` 管道表格、段落；
 * 行内：`**加粗**`、`[fact_xxx]` 证据引用。
 */

export type InlineToken =
  | { t: 'text'; v: string }
  | { t: 'bold'; v: string }
  | { t: 'factref'; key: string }

export type Block =
  | { t: 'h2'; inlines: InlineToken[] }
  | { t: 'p'; inlines: InlineToken[] }
  | { t: 'ul'; items: InlineToken[][] }
  | { t: 'table'; header: InlineToken[][]; rows: InlineToken[][][] }

const INLINE_RE = /\*\*([^*]+)\*\*|\[(fact_[A-Za-z0-9_]+)\]/g

export function parseInline(s: string): InlineToken[] {
  const out: InlineToken[] = []
  let last = 0
  for (const m of s.matchAll(INLINE_RE)) {
    if (m.index! > last) out.push({ t: 'text', v: s.slice(last, m.index) })
    if (m[1] !== undefined) out.push({ t: 'bold', v: m[1] })
    else out.push({ t: 'factref', key: m[2]! })
    last = m.index! + m[0].length
  }
  if (last < s.length) out.push({ t: 'text', v: s.slice(last) })
  return out
}

function isTableRow(t: string): boolean {
  return t.startsWith('|') && t.endsWith('|') && t.length > 2
}

function isSeparatorRow(t: string): boolean {
  return isTableRow(t) && /^\|[\s:|-]+\|$/.test(t)
}

function splitCells(t: string): InlineToken[][] {
  return t
    .slice(1, -1)
    .split('|')
    .map((c) => parseInline(c.trim()))
}

export function parseReportMd(md: string): Block[] {
  const lines = String(md).split('\n')
  const blocks: Block[] = []
  let ul: InlineToken[][] | null = null
  let table: { header: InlineToken[][]; rows: InlineToken[][][] } | null = null

  const flushUl = () => {
    if (ul) {
      blocks.push({ t: 'ul', items: ul })
      ul = null
    }
  }
  const flushTable = () => {
    if (table) {
      blocks.push({ t: 'table', ...table })
      table = null
    }
  }

  for (const line of lines) {
    const t = line.trim()
    if (!t.startsWith('- ')) flushUl()
    if (!isTableRow(t)) flushTable()
    if (!t) continue

    if (t.startsWith('## ')) {
      blocks.push({ t: 'h2', inlines: parseInline(t.slice(3)) })
    } else if (t.startsWith('# ')) {
      blocks.push({ t: 'h2', inlines: parseInline(t.slice(2)) })
    } else if (t.startsWith('- ')) {
      ul = ul ?? []
      ul.push(parseInline(t.slice(2)))
    } else if (isTableRow(t)) {
      if (isSeparatorRow(t)) continue
      if (!table) table = { header: splitCells(t), rows: [] }
      else table.rows.push(splitCells(t))
    } else {
      blocks.push({ t: 'p', inlines: parseInline(t) })
    }
  }
  flushUl()
  flushTable()
  return blocks
}
