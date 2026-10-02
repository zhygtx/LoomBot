export type ConnectionDirection = 'FORWARD' | 'REVERSE'

export type ConnectionRuntimeState =
  | 'DISABLED'
  | 'PENDING'
  | 'STARTING'
  | 'CONNECTING'
  | 'LISTENING'
  | 'ONLINE'
  | 'RETRY_WAIT'
  | 'DEGRADED'
  | 'STOPPING'
  | 'FAILED'

export interface JsonSchemaNode {
  type?: 'object' | 'string' | 'number' | 'integer' | 'boolean' | 'array'
  title?: string
  description?: string
  default?: unknown
  enum?: string[]
  format?: string
  required?: string[]
  properties?: Record<string, JsonSchemaNode>
  'x-secret'?: boolean
  'x-order'?: number
  'x-generate'?: 'random32' | 'uuid'
  'x-widget'?: string
}

export interface ConnectionTypeDescriptor {
  pluginId: string
  pluginVersionId: string
  pluginKey: string
  pluginName: string
  pluginVersion: string
  type: string
  displayName: string
  direction: ConnectionDirection
  configSchema: JsonSchemaNode
  capabilities: string[]
  adapterName: string
}

export interface ConnectionRuntimeStatus {
  connectionId: string
  enabled: boolean
  state: ConnectionRuntimeState
  failureReason: string | null
  revisionPending: number
  desiredRevision: number
  runtimeReachable: boolean
  observedRevision: number | null
  lastFrameAt: number
  instanceId: string | null
  lastSyncedAt: string | null
}

export interface ConnectionRecord {
  id: string
  name: string
  pluginVersionId: string
  connectionType: string
  config: Record<string, unknown> | null
  endpointPath: string | null
  endpointUrl: string | null
  ownerUserId: string | null
  enabled: boolean
  remark: string | null
  createTime: string
  updateTime: string
  status: ConnectionRuntimeStatus
}

export interface ConnectionPage {
  records: ConnectionRecord[]
  total: number
  pageNum: number
  pageSize: number
  pages: number
}

export interface ConnectionCreatePayload {
  name: string
  pluginVersionId: string
  connectionType: string
  config: Record<string, unknown>
  remark: string | null
}
