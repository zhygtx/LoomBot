import { apiRequest } from '@shared/api/http-client'

/** 工作流定义：节点身份是 pluginVersionId + nodeKey，参数按 paramName 绑定。 */
export interface WorkflowNodeInput {
  paramName: string
  source?: string
  defaultValue?: unknown
}

export interface WorkflowNodeBranch {
  mode: 'truthy' | 'expression'
  expression?: string
}

export interface WorkflowNode {
  id: string
  x: number
  y: number
  pluginVersionId?: string | null
  nodeKey: string
  /** 保存时的中文展示名快照；运行时把它写进执行日志。 */
  name?: string | null
  connectionType?: string | null
  connectionId?: string | null
  inputs?: WorkflowNodeInput[]
  branch?: WorkflowNodeBranch | null
  config?: Record<string, unknown>
  /** 节点目录里的声明，由画布在加载时补齐；只用于渲染与参数绑定。 */
  descriptor?: WorkflowNodeCatalogItem | null
}

export interface WorkflowEdge {
  from: string
  to: string
  port?: 'success' | 'failure'
}

export interface WorkflowNodeParameter {
  name: string
  displayName?: string
  type?: string
  required?: boolean
  nullable?: boolean
  description?: string
  defaultValue?: unknown
  order?: number
  kind?: string
}

export interface WorkflowReturnField {
  name?: string
  displayName?: string
  key?: string
  type?: string
  description?: string
  path?: string
  depth?: number
}

export interface WorkflowDefinition {
  eventNodeId: string
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  canvas?: { offsetX: number; offsetY: number; scale: number }
}

export interface WorkflowSummary {
  id: string
  name: string
  description?: string | null
  enabled: number
  currentVersionId?: string | null
}

export interface WorkflowDetail {
  workflow: WorkflowSummary
  versionNo?: number
  definition?: WorkflowDefinition
}

export interface WorkflowNodeCatalogItem {
  pluginVersionId?: string | null
  pluginKey?: string
  pluginVersion?: string
  nodeKey: string
  nodeType: 'EVENT' | 'ACTION' | 'NODE'
  connectionType?: string | null
  name: string
  description?: string
  sourceRef?: string
  parameters?: WorkflowNodeParameter[]
  returnFields?: WorkflowReturnField[]
  returnType?: string
  category?: string
}

export interface WorkflowNodeCatalog {
  nodes: WorkflowNodeCatalogItem[]
  systemNodes: WorkflowNodeCatalogItem[]
}

/** 执行日志列表项。不含 detail_json，明细走详情接口。 */
export interface WorkflowExecutionSummary {
  id: string
  executionId: string
  workflowId: string
  workflowName?: string | null
  definitionVersion?: number | null
  connectionId?: string | null
  connectionType?: string | null
  /** 事件节点绑定连接的名称快照；连接已删除时为空。 */
  connectionName?: string | null
  triggerType?: 'EVENT' | 'SCHEDULE' | 'TEST' | string
  /** 事件节点键与中文名快照。 */
  eventNodeKey?: string | null
  eventNodeName?: string | null
  /** 事件节点输出的摘要。 */
  eventSummary?: string | null
  status: string
  errorCode?: string | null
  errorMessage?: string | null
  detailTruncated?: number | null
  startTime?: string | null
  endTime?: string | null
  durationMs?: number | null
}

/** 执行明细里的一个节点。 */
export interface WorkflowTraceNode {
  nodeId?: string
  nodeKey?: string
  name?: string | null
  status: string
  startTime?: number | null
  endTime?: number | null
  input?: unknown
  output?: unknown
  runtimeFields?: Array<Record<string, unknown>>
  error?: string | null
  /** false 表示这次调用只确认"发出去了"，没拿到结果（例如平台没回响应）。 */
  resultKnown?: boolean
}

export interface WorkflowTrace {
  trigger?: {
    eventNodeKey?: string | null
    eventNodeName?: string | null
    connectionId?: string | null
    eventSummary?: string | null
  } | null
  nodes?: WorkflowTraceNode[]
  resultKnown?: boolean
  truncated?: boolean
  detailUnavailable?: boolean
}

export interface WorkflowExecutionDetail {
  summary: WorkflowExecutionSummary
  trace?: WorkflowTrace | null
}

/** 执行时绑定的定义快照；历史日志模式画布按它渲染。 */
export interface WorkflowExecutionDefinition {
  workflowId?: string | null
  workflowName?: string | null
  versionNo?: number | null
  /** 快照是否还在（版本被清理或记录太旧时可能没有）。 */
  definitionAvailable: boolean
  definition?: WorkflowDefinition | null
}

export interface ExecutionListQuery {
  workflowId?: string
  status?: string
  includeTest?: boolean
  /** 内容关键词：匹配节点的输入输出、事件摘要和错误信息。 */
  keyword?: string
  beforeId?: string
  size?: number
}

export interface WorkflowTestNodeResult {
  nodeId: string
  nodeKey: string
  name?: string
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | string
  input?: unknown
  output?: unknown
  runtimeFields?: Array<Record<string, unknown>>
  error?: string | null
  resultKnown?: boolean
  startTime?: number
  endTime?: number | null
}

export interface WorkflowTestResult {
  executionId: string
  status: string
  errorCode?: string | null
  errorMessage?: string | null
  durationMs: number
  nodes: WorkflowTestNodeResult[]
  terminalNodeIds: string[]
}

export interface WorkflowSavePayload {
  id?: string | null
  name: string
  description?: string
  definition: WorkflowDefinition
}

export const listWorkflows = () => apiRequest<WorkflowSummary[]>({ url: '/workflow/list' })

export const getWorkflow = (id: string) => apiRequest<WorkflowDetail>({ url: `/workflow/${id}` })

export const loadNodeCatalog = () => apiRequest<WorkflowNodeCatalog>({ url: '/workflow/nodes' })

export const saveWorkflow = (payload: WorkflowSavePayload) =>
  apiRequest<{ workflowId: string; versionId: string; versionNo: number }>({
    url: '/workflow/save',
    method: 'post',
    data: payload,
  })

export const setWorkflowEnabled = (id: string, enabled: boolean) =>
  apiRequest<boolean>({ url: `/workflow/${id}/enabled`, method: 'post', params: { enabled } })

export const deleteWorkflow = (id: string) =>
  apiRequest<boolean>({ url: `/workflow/${id}`, method: 'delete' })

/**
 * 保存并测试。超时单独放宽到 90 秒：一次执行可能包含要等平台响应的适配器动作，
 * 单个动作最长等 30 秒，用全局的 15 秒会在后端还在跑的时候就把前端掐掉，
 * 用户只能看到"请求超时"，拿不到真正的失败原因。
 */
export const testWorkflow = (id: string) =>
  apiRequest<WorkflowTestResult>({
    url: `/workflow/${id}/run`,
    method: 'post',
    timeout: 90_000,
  })

/** 执行日志列表。编辑器抽屉复用同一个接口，只是固定传 workflowId。 */
export const listExecutionLog = (query: ExecutionListQuery = {}) =>
  apiRequest<WorkflowExecutionSummary[]>({ url: '/workflow/executions', params: { ...query } })

export const getExecution = (executionId: string) =>
  apiRequest<WorkflowExecutionDetail>({ url: `/workflow/executions/${executionId}` })

/** 执行时那份定义快照：历史日志模式下用来还原当时的画布。 */
export const getExecutionDefinition = (executionId: string) =>
  apiRequest<WorkflowExecutionDefinition>({
    url: `/workflow/executions/${executionId}/definition`,
  })

/**
 * 取一条大内容的完整正文。
 *
 * <p>日志详情里只放 `{ref, size, sha256, preview}` 标记，正文留在后端；列表和详情都不会带上它，
 * 只有用户点开时才调这里。
 */
export const getExecutionPayload = (executionId: string, ref: string) =>
  apiRequest<string>({ url: `/workflow/executions/${executionId}/payloads/${ref}` })

/** 落盘文件的下载地址；调用方需要自己带 Authorization 头去取。 */
export const executionFileUrl = (executionId: string, fileName: string) =>
  `/api/workflow/executions/${encodeURIComponent(executionId)}/files/${encodeURIComponent(fileName)}`
