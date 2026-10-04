/** 工作流执行相关的展示格式化，测试结果弹窗和执行日志共用。 */

const STATUS_LABELS: Record<string, string> = {
  RUNNING: '执行中',
  SUCCESS: '成功',
  SUCCEEDED: '成功',
  FAILED: '失败',
  TIMEOUT: '超时',
  CANCELLED: '已取消',
}

const TRIGGER_LABELS: Record<string, string> = {
  EVENT: '平台事件',
  SCHEDULE: '定时任务',
  TEST: '测试执行',
}

export function statusLabel(status: string | null | undefined): string {
  if (!status) return '未知'
  return STATUS_LABELS[status] ?? status
}

export function isSuccessStatus(status: string | null | undefined): boolean {
  return status === 'SUCCESS' || status === 'SUCCEEDED'
}

export function triggerLabel(triggerType: string | null | undefined): string {
  if (!triggerType) return '未知来源'
  return TRIGGER_LABELS[triggerType] ?? triggerType
}

/**
 * 日志里「触发来源」的一行描述：`触发类型 · 连接名 · 事件节点名`。
 *
 * <p>测试执行没有连接和事件节点，只显示触发类型——再拼一次就成了「测试执行 · 测试执行」。
 */
export function triggerSummary(execution: {
  triggerType?: string | null
  connectionId?: string | null
  connectionName?: string | null
  eventNodeName?: string | null
  eventNodeKey?: string | null
}): string {
  const trigger = triggerLabel(execution.triggerType)
  if (execution.triggerType === 'TEST') return trigger
  const name = execution.eventNodeName || execution.eventNodeKey || '未知事件'
  const connection =
    execution.connectionName || (execution.connectionId ? '连接已删除' : '')
  return connection ? `${trigger} · ${connection} · ${name}` : `${trigger} · ${name}`
}

export function formatValue(value: unknown): string {
  if (value === null || value === undefined) return '无'
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/**
 * 后端存的大内容正文是紧凑 JSON（json.dumps 的默认分隔符，没有换行和缩进），
 * 展示前先解析再美化；解析不了就按纯文本原样显示。
 */
export function formatRawContent(text: string): string {
  const trimmed = text.trim()
  if (!trimmed || (trimmed[0] !== '{' && trimmed[0] !== '[')) return text
  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2)
  } catch {
    return text
  }
}

export function formatTime(value?: string | null): string {
  if (!value) return '未知时间'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

export function formatDuration(value?: number | null): string {
  if (value === null || value === undefined) return '—'
  if (value < 1000) return `${value} ms`
  return `${(value / 1000).toFixed(value < 10_000 ? 2 : 1)} s`
}

export function formatBytes(value?: number | null): string {
  if (value === null || value === undefined) return '未知大小'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / (1024 * 1024)).toFixed(2)} MB`
}

/** 后端写进日志的大内容引用标记。 */
export interface ValueMarker {
  truncated: true
  kind?: 'text' | 'file'
  type?: string
  ref?: string
  size?: number
  sha256?: string
  preview?: string
  fileName?: string
  contentType?: string
  contentOmitted?: boolean
}

export function asValueMarker(value: unknown): ValueMarker | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const item = value as Record<string, unknown>
  if (item.truncated !== true) return null
  return {
    truncated: true,
    kind: item.kind === 'file' ? 'file' : item.kind === 'text' ? 'text' : undefined,
    type: typeof item.type === 'string' ? item.type : undefined,
    ref: typeof item.ref === 'string' ? item.ref : undefined,
    size: typeof item.size === 'number' ? item.size : undefined,
    sha256: typeof item.sha256 === 'string' ? item.sha256 : undefined,
    preview: typeof item.preview === 'string' ? item.preview : undefined,
    fileName: typeof item.fileName === 'string' ? item.fileName : undefined,
    contentType: typeof item.contentType === 'string' ? item.contentType : undefined,
    contentOmitted: item.contentOmitted === true,
  }
}
