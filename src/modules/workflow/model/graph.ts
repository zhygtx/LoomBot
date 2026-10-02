import type {
  WorkflowEdge,
  WorkflowNode,
  WorkflowNodeCatalogItem,
  WorkflowNodeInput,
  WorkflowNodeParameter,
} from '../api/workflow-api'

export const NODE_WIDTH = 250
export const NODE_FALLBACK_HEIGHT = 104

export function catalogIdentity(item: WorkflowNodeCatalogItem): string {
  return item.pluginVersionId ? `${item.pluginVersionId}:${item.nodeKey}` : item.nodeKey
}

export function catalogLabel(item: WorkflowNodeCatalogItem): string {
  return item.name?.trim() || item.pluginKey?.trim() || item.nodeKey
}

export function nodeLabel(node: WorkflowNode | null | undefined): string {
  return node?.descriptor?.name?.trim() || node?.nodeKey || '未命名节点'
}

export function isAdapterNode(node: WorkflowNode | null | undefined): boolean {
  return Boolean(node?.connectionType)
}

export function isEventNode(node: WorkflowNode | null | undefined): boolean {
  return node?.descriptor?.nodeType === 'EVENT'
}

export function nodeTypeLabel(node: WorkflowNode | null | undefined): string {
  if (isEventNode(node)) return '事件'
  if (node?.branch) return '分支'
  if (node?.descriptor?.nodeType === 'ACTION') return '动作'
  // 兜底：绑了连接但目录里查不到声明（插件版本换了）时，只能按适配器节点显示
  if (isAdapterNode(node)) return '适配器'
  return '节点'
}

export function catalogNodeTypeLabel(item: WorkflowNodeCatalogItem): string {
  if (item.nodeType === 'EVENT') return '事件'
  if (item.nodeType === 'ACTION') return '动作'
  return '节点'
}

export type WorkflowNodeKind = 'EVENT' | 'ACTION' | 'NODE'

/** 节点种类：事件（含定时任务）/ 动作 / 普通节点。画布与插件列表共用同一判定。 */
export function nodeKindOf(node: WorkflowNode | null | undefined): WorkflowNodeKind {
  if (isEventNode(node)) return 'EVENT'
  if (node?.descriptor?.nodeType === 'ACTION') return 'ACTION'
  return 'NODE'
}

export function catalogNodeKind(item: WorkflowNodeCatalogItem): WorkflowNodeKind {
  if (item.nodeType === 'EVENT') return 'EVENT'
  if (item.nodeType === 'ACTION') return 'ACTION'
  return 'NODE'
}

/**
 * 每种节点的主色：事件棕、动作绿、普通节点蓝。
 *
 * <p>画布节点卡片和插件列表条目都只认这一个映射，颜色不会两处各写一份。
 * 将来做用户自定义样式时，替换这里（或让用户覆盖 `--node-accent`）即可整体换肤。
 */
const NODE_ACCENTS: Record<WorkflowNodeKind, string> = {
  EVENT: 'var(--sys-color-warning-text)',
  ACTION: 'var(--sys-color-success-text)',
  NODE: 'var(--sys-color-action-primary)',
}

export function nodeAccent(kind: WorkflowNodeKind): string {
  return NODE_ACCENTS[kind]
}

export function sortParameters(
  parameters: WorkflowNodeParameter[] | undefined,
): WorkflowNodeParameter[] {
  return [...(parameters ?? [])].sort((left, right) => {
    const order = (left.order ?? 0) - (right.order ?? 0)
    return order || left.name.localeCompare(right.name, 'zh-CN')
  })
}

/** 参数是否已显式配置：选了引用，或填过默认值（false / 0 / 空串都算填过）。 */
export function inputConfigured(input: WorkflowNodeInput | undefined): boolean {
  if (!input) return false
  if (String(input.source ?? '').trim()) return true
  return input.defaultValue !== null && input.defaultValue !== undefined
}

/** 第一个「必填但没配置」的参数；没有则返回 undefined。 */
export function missingParameter(node: WorkflowNode): WorkflowNodeParameter | undefined {
  // 事件节点的参数来自平台事件本身，运行时不读 inputs，所以不参与这项校验。
  if (isEventNode(node)) return undefined
  return sortParameters(node.descriptor?.parameters).find((parameter) => {
    if (!parameter.required) return false
    const input = node.inputs?.find((item) => item.paramName === parameter.name)
    return !inputConfigured(input)
  })
}

export function parameterLabel(parameter: WorkflowNodeParameter): string {
  return parameter.displayName?.trim() || parameter.name
}

export function createNodeFromCatalog(
  item: WorkflowNodeCatalogItem,
  x: number,
  y: number,
): WorkflowNode {
  const parameters = sortParameters(item.parameters)
  const config: Record<string, unknown> = {}
  if (item.nodeKey === 'system.schedule') {
    config.cron = '0 0 8 * * ?'
  }
  return {
    id: `node_${Date.now()}_${Math.floor(Math.random() * 10_000)}`,
    x,
    y,
    pluginVersionId: item.pluginVersionId ?? null,
    nodeKey: item.nodeKey,
    name: item.name ?? null,
    connectionType: item.connectionType ?? null,
    connectionId: null,
    // 不预填插件声明的默认值：参数必须由使用者在画布上显式配置。
    inputs: parameters.map((parameter) => ({
      paramName: parameter.name,
      source: '',
      defaultValue: null,
    })),
    branch: null,
    config,
    descriptor: item,
  }
}

export function attachDescriptor(
  node: WorkflowNode,
  catalog: WorkflowNodeCatalogItem[],
): WorkflowNode {
  const descriptor =
    catalog.find(
      (item) => item.pluginVersionId === node.pluginVersionId && item.nodeKey === node.nodeKey,
    ) ??
    catalog.find((item) => !node.pluginVersionId && item.nodeKey === node.nodeKey) ??
    null
  return {
    ...node,
    descriptor,
    // 旧定义里没有 name 时补上，保证执行日志里能显示中文节点名
    name: descriptor?.name ?? node.name ?? null,
  }
}

export function edgeIdentity(edge: WorkflowEdge): string {
  return `${edge.from}:${edge.to}:${edge.port ?? 'success'}`
}

export function edgePath(
  edge: WorkflowEdge,
  nodes: WorkflowNode[],
  nodeHeights: Record<string, number>,
): string {
  const from = nodes.find((node) => node.id === edge.from)
  const to = nodes.find((node) => node.id === edge.to)
  if (!from || !to) return ''
  const fromHeight = nodeHeights[from.id] ?? NODE_FALLBACK_HEIGHT
  const toHeight = nodeHeights[to.id] ?? NODE_FALLBACK_HEIGHT
  const startX = from.x + NODE_WIDTH
  const startY =
    edge.port === 'failure'
      ? from.y + fromHeight * 0.74
      : from.y + (from.branch ? fromHeight * 0.36 : fromHeight / 2)
  const endX = to.x
  const endY = to.y + toHeight / 2
  const offset = Math.max(70, Math.abs(endX - startX) * 0.48)
  return `M ${startX} ${startY} C ${startX + offset} ${startY}, ${endX - offset} ${endY}, ${endX} ${endY}`
}

export function wouldCreateCycle(
  fromNodeId: string,
  toNodeId: string,
  edges: WorkflowEdge[],
): boolean {
  const visited = new Set<string>()
  const stack = [toNodeId]
  while (stack.length) {
    const current = stack.pop()
    if (!current) continue
    if (current === fromNodeId) return true
    if (visited.has(current)) continue
    visited.add(current)
    for (const edge of edges) {
      if (edge.from === current) stack.push(edge.to)
    }
  }
  return false
}

export function connectionError(
  from: WorkflowNode,
  to: WorkflowNode,
  port: 'success' | 'failure',
  edges: WorkflowEdge[],
): string {
  if (from.id === to.id) return '节点不能连接自身'
  if (isEventNode(to)) return '事件节点不能作为连线目标'
  if (port === 'failure' && !from.branch) return '普通节点只有成功输出'
  if (wouldCreateCycle(from.id, to.id, edges)) return '这条连线会形成环'
  return ''
}
