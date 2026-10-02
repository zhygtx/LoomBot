<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Blocks,
  Hand,
  History,
  LocateFixed,
  MousePointer2,
  Play,
  Power,
  Save,
  X,
} from '@lucide/vue'

import { connectionsApi, type ConnectionRecord } from '@modules/connections'
import { ApiError } from '@shared/api/http-client'
import { BaseButton, message } from '@shared/ui'

import {
  getExecution,
  getExecutionDefinition,
  getWorkflow,
  loadNodeCatalog,
  saveWorkflow,
  setWorkflowEnabled,
  testWorkflow,
  type WorkflowDefinition,
  type WorkflowEdge,
  type WorkflowExecutionDetail,
  type WorkflowExecutionSummary,
  type WorkflowNode,
  type WorkflowNodeCatalog,
  type WorkflowNodeCatalogItem,
  type WorkflowTestResult,
  type WorkflowTraceNode,
} from '../api/workflow-api'
import {
  NODE_FALLBACK_HEIGHT,
  NODE_WIDTH,
  attachDescriptor,
  catalogIdentity,
  connectionError,
  createNodeFromCatalog,
  edgeIdentity,
  isAdapterNode,
  isEventNode,
  missingParameter,
  nodeLabel,
  parameterLabel,
} from '../model/graph'
import {
  formatDuration,
  formatTime,
  isSuccessStatus,
  statusLabel,
  triggerLabel,
} from '../model/format'
import WorkflowConnections, { type TempConnection } from '../ui/WorkflowConnections.vue'
import WorkflowHistoryPanel from '../ui/WorkflowHistoryPanel.vue'
import WorkflowInspector from '../ui/WorkflowInspector.vue'
import WorkflowNodeCard from '../ui/WorkflowNodeCard.vue'
import WorkflowNodeTraceCard from '../ui/WorkflowNodeTraceCard.vue'
import WorkflowPalette from '../ui/WorkflowPalette.vue'
import WorkflowTestResultDialog from '../ui/WorkflowTestResultDialog.vue'

type EditorMode = 'drag' | 'select'

interface DragNodeState {
  node: WorkflowNode
  pointerId: number
  clientX: number
  clientY: number
  nodeX: number
  nodeY: number
  moved: boolean
}

interface PanState {
  pointerId: number
  clientX: number
  clientY: number
  offsetX: number
  offsetY: number
}

interface SelectionState {
  pointerId: number
  startX: number
  startY: number
  endX: number
  endY: number
}

interface ContextMenuState {
  visible: boolean
  x: number
  y: number
  kind: 'canvas' | 'node' | 'edge'
  node?: WorkflowNode
  edge?: WorkflowEdge
}

interface PinchState {
  distance: number
  scale: number
  worldX: number
  worldY: number
}

const route = useRoute()
const router = useRouter()

const workflowId = ref<string | null>(typeof route.params.id === 'string' ? route.params.id : null)
const name = ref('新建工作流')
const description = ref('')
const enabled = ref(true)
const nodes = ref<WorkflowNode[]>([])
const edges = ref<WorkflowEdge[]>([])
const catalog = ref<WorkflowNodeCatalog>({ nodes: [], systemNodes: [] })
const connections = ref<ConnectionRecord[]>([])
const canvas = reactive({ offsetX: 0, offsetY: 0, scale: 1 })
const mode = ref<EditorMode>('drag')
const selectedId = ref<string | null>(null)
const configNodeId = ref<string | null>(null)
const isMobile = ref(window.matchMedia('(max-width: 56rem)').matches)
const paletteOpen = ref(!isMobile.value)
const loading = ref(true)
const saving = ref(false)
const loadingCatalog = ref(true)
const editingName = ref(false)
const historyOpen = ref(false)
const testResult = ref<WorkflowTestResult | null>(null)
const nodeHeights = ref<Record<string, number>>({})

/** 历史日志模式：选中某次执行后画布切到只读，节点下方挂那次执行的输入输出。 */
const executionView = ref<WorkflowExecutionSummary | null>(null)
const executionTrace = ref<WorkflowExecutionDetail | null>(null)
const loadingExecution = ref(false)
/** 执行时那份定义快照没取到（记录太旧/版本被清理），退回当前定义并提示。 */
const executionSnapshotMissing = ref(false)
/** 进历史模式前的编辑态，退出时原样还原，避免丢掉未保存的改动。 */
let editingSnapshot: {
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  canvas: { offsetX: number; offsetY: number; scale: number }
  paletteOpen: boolean
} | null = null

const canvasRef = ref<HTMLElement | null>(null)
const worldRef = ref<HTMLElement | null>(null)
const draggedItem = ref<WorkflowNodeCatalogItem | null>(null)
const draggingNode = ref<DragNodeState | null>(null)
const panning = ref<PanState | null>(null)
const selection = ref<SelectionState | null>(null)
const connecting = ref<{ node: WorkflowNode; port: 'success' | 'failure' } | null>(null)
const connectingPointerId = ref<number | null>(null)
const tempConnection = ref<TempConnection | null>(null)
const activePointers = new Map<number, { x: number; y: number }>()
const pinchState = ref<PinchState | null>(null)
let longPressTimer: number | undefined
let longPressPoint: { x: number; y: number; node: WorkflowNode } | null = null
const contextMenu = reactive<ContextMenuState>({
  visible: false,
  x: 0,
  y: 0,
  kind: 'canvas',
})

const allCatalog = computed(() => [...catalog.value.nodes, ...catalog.value.systemNodes])
const selectedNode = computed(
  () => nodes.value.find((node) => node.id === selectedId.value) ?? null,
)
const configNode = computed(
  () => nodes.value.find((node) => node.id === configNodeId.value) ?? null,
)
const eventNode = computed(() => nodes.value.find((node) => isEventNode(node)) ?? null)
const lockedAdapterPluginVersionId = computed(
  () =>
    nodes.value.find((node) => isAdapterNode(node) && node.pluginVersionId)?.pluginVersionId ??
    null,
)
const historyMode = computed(() => Boolean(executionView.value))
/** 画布节点 id -> 那次执行的 trace；没有就是这次没走到。 */
const traceByNodeId = computed<Record<string, WorkflowTraceNode>>(() => {
  const map: Record<string, WorkflowTraceNode> = {}
  for (const node of executionTrace.value?.trace?.nodes ?? []) {
    if (node.nodeId) map[node.nodeId] = node
  }
  return map
})
/** 这次执行实际走到的节点数，和快照里的节点总数一起显示在状态条上。 */
const executedNodeCount = computed(() => Object.keys(traceByNodeId.value).length)

/** 历史模式下每个节点的执行状态：成功绿、失败红、没走到灰。 */
function executionStatusOf(nodeId: string): 'success' | 'failed' | 'skipped' {
  const trace = traceByNodeId.value[nodeId]
  if (!trace) return 'skipped'
  return isSuccessStatus(trace.status) ? 'success' : 'failed'
}
const canvasWorldStyle = computed(() => ({
  transform: `translate(${canvas.offsetX}px, ${canvas.offsetY}px) scale(${canvas.scale})`,
}))
const canvasBackgroundStyle = computed(() => {
  const gridSize = 20 * canvas.scale
  const dotRadius = 1.2 * canvas.scale
  return {
    backgroundPosition: `${canvas.offsetX}px ${canvas.offsetY}px`,
    backgroundSize: `${gridSize}px ${gridSize}px`,
    backgroundImage: `radial-gradient(circle, color-mix(in srgb, var(--sys-color-text-muted) 32%, transparent) ${dotRadius}px, transparent ${dotRadius}px)`,
  }
})
const selectionStyle = computed(() => {
  if (!selection.value) return {}
  const width = Math.abs(selection.value.endX - selection.value.startX)
  const height = Math.abs(selection.value.endY - selection.value.startY)
  return {
    left: `${Math.min(selection.value.startX, selection.value.endX)}px`,
    top: `${Math.min(selection.value.startY, selection.value.endY)}px`,
    width: `${width}px`,
    height: `${height}px`,
  }
})

const measureNodeHeights = (): void => {
  const next: Record<string, number> = {}
  worldRef.value?.querySelectorAll<HTMLElement>('[data-node-id]').forEach((element) => {
    const id = element.dataset.nodeId
    if (id) next[id] = element.offsetHeight
  })
  nodeHeights.value = next
}

const loadCatalog = async (): Promise<void> => {
  loadingCatalog.value = true
  try {
    catalog.value = await loadNodeCatalog()
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '节点目录加载失败')
  } finally {
    loadingCatalog.value = false
  }
}

const loadWorkflow = async (): Promise<void> => {
  if (!workflowId.value) return
  try {
    const detail = await getWorkflow(workflowId.value)
    name.value = detail.workflow.name
    description.value = detail.workflow.description ?? ''
    enabled.value = detail.workflow.enabled === 1
    const definition = detail.definition
    if (!definition) return
    nodes.value = (definition.nodes ?? []).map((node) => attachDescriptor(node, allCatalog.value))
    edges.value = definition.edges ?? []
    if (definition.canvas) {
      Object.assign(canvas, definition.canvas)
    } else {
      await nextTick()
      locateInitialNode(false)
    }
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '工作流加载失败')
  }
}

const loadConnections = async (): Promise<void> => {
  try {
    const page = await connectionsApi.list()
    connections.value = page.records
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '协议适配器连接加载失败')
  }
}

const saveWorkflowName = (): void => {
  editingName.value = false
  if (!name.value.trim()) name.value = '新建工作流'
}

const toggleMode = (nextMode: EditorMode): void => {
  mode.value = nextMode
}

const insertNode = (item: WorkflowNodeCatalogItem, x: number, y: number): void => {
  if (historyMode.value) return
  if (isEventNodeFromCatalog(item) && eventNode.value) {
    message.error('画布上只能有一个事件节点')
    return
  }
  if (item.connectionType) {
    if (
      lockedAdapterPluginVersionId.value &&
      item.pluginVersionId !== lockedAdapterPluginVersionId.value
    ) {
      message.error('当前画布已经使用了其他协议适配器，不能混用不同适配器的节点')
      return
    }
    const hasConnection = connections.value.some(
      (connection) =>
        connection.enabled &&
        connection.pluginVersionId === item.pluginVersionId &&
        connection.connectionType === item.connectionType,
    )
    if (!hasConnection) {
      message.error('这个协议适配器还没有可用连接')
      return
    }
  }
  const node = createNodeFromCatalog(item, x, y)
  nodes.value = [...nodes.value, node]
  selectedId.value = node.id
  historyOpen.value = false
  void nextTick(measureNodeHeights)
}

const addNodeAtCenter = (_event: MouseEvent, item: WorkflowNodeCatalogItem): void => {
  const rect = canvasRef.value?.getBoundingClientRect()
  if (!rect) return
  const offset = nodes.value.length * 18
  insertNode(
    item,
    (rect.width / 2 - canvas.offsetX) / canvas.scale - NODE_WIDTH / 2 + offset,
    (rect.height / 2 - canvas.offsetY) / canvas.scale - 64 + offset,
  )
  if (isMobile.value) paletteOpen.value = false
}

const startPaletteDrag = (event: DragEvent, item: WorkflowNodeCatalogItem): void => {
  draggedItem.value = item
  event.dataTransfer?.setData('text/plain', catalogIdentity(item))
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy'
}

const endPaletteDrag = (): void => {
  draggedItem.value = null
}

const handleDrop = (event: DragEvent): void => {
  const item = draggedItem.value
  endPaletteDrag()
  if (!item || !canvasRef.value) return
  const rect = canvasRef.value.getBoundingClientRect()
  insertNode(
    item,
    (event.clientX - rect.left - canvas.offsetX) / canvas.scale - NODE_WIDTH / 2,
    (event.clientY - rect.top - canvas.offsetY) / canvas.scale - 56,
  )
}

const clearLongPress = (): void => {
  if (longPressTimer !== undefined) {
    window.clearTimeout(longPressTimer)
    longPressTimer = undefined
  }
  longPressPoint = null
}

const scheduleLongPress = (event: PointerEvent, node: WorkflowNode): void => {
  if (event.pointerType !== 'touch') return
  clearLongPress()
  longPressPoint = { x: event.clientX, y: event.clientY, node }
  longPressTimer = window.setTimeout(() => {
    const point = longPressPoint
    if (!point || draggingNode.value?.moved) return
    draggingNode.value = null
    openNodeContextMenu(
      new MouseEvent('contextmenu', { clientX: point.x, clientY: point.y }),
      point.node,
    )
    clearLongPress()
  }, 560)
}

const openNodeConfig = (node: WorkflowNode): void => {
  if (historyMode.value) return
  selectedId.value = node.id
  configNodeId.value = node.id
  historyOpen.value = false
}

const startNodeMove = (event: PointerEvent, node: WorkflowNode): void => {
  if (historyMode.value) return
  if (event.pointerType === 'mouse' && event.button !== 0) return
  selectedId.value = node.id
  historyOpen.value = false
  draggingNode.value = {
    node,
    pointerId: event.pointerId,
    clientX: event.clientX,
    clientY: event.clientY,
    nodeX: node.x,
    nodeY: node.y,
    moved: false,
  }
  scheduleLongPress(event, node)
}

const startConnection = (
  event: PointerEvent,
  node: WorkflowNode,
  port: 'success' | 'failure',
): void => {
  if (historyMode.value) return
  if (event.pointerType === 'mouse' && event.button !== 0) return
  if (port === 'failure' && !node.branch) return
  const rect = canvasRef.value?.getBoundingClientRect()
  if (!rect) return
  connecting.value = { node, port }
  connectingPointerId.value = event.pointerId
  tempConnection.value = {
    from: node.id,
    port,
    x: (event.clientX - rect.left - canvas.offsetX) / canvas.scale,
    y: (event.clientY - rect.top - canvas.offsetY) / canvas.scale,
  }
}

const finishConnection = (event: PointerEvent): void => {
  const current = connecting.value
  connecting.value = null
  connectingPointerId.value = null
  tempConnection.value = null
  if (!current) return
  const target = (event.target as HTMLElement | null)?.closest<HTMLElement>('[data-node-id]')
  const targetId = target?.dataset.nodeId
  const targetNode = nodes.value.find((node) => node.id === targetId)
  if (!targetNode) return
  const error = connectionError(current.node, targetNode, current.port, edges.value)
  if (error) {
    message.error(error)
    return
  }
  const edge: WorkflowEdge = {
    from: current.node.id,
    to: targetNode.id,
    port: current.port,
  }
  if (!edges.value.some((item) => edgeIdentity(item) === edgeIdentity(edge))) {
    edges.value = [...edges.value, edge]
  }
}

const beginPinch = (): void => {
  if (activePointers.size < 2 || !canvasRef.value) return
  const points = [...activePointers.values()]
  const first = points[0]
  const second = points[1]
  if (!first || !second) return
  const rect = canvasRef.value.getBoundingClientRect()
  const distance = Math.hypot(second.x - first.x, second.y - first.y)
  if (distance < 1) return
  const middleX = (first.x + second.x) / 2
  const middleY = (first.y + second.y) / 2
  pinchState.value = {
    distance,
    scale: canvas.scale,
    worldX: (middleX - rect.left - canvas.offsetX) / canvas.scale,
    worldY: (middleY - rect.top - canvas.offsetY) / canvas.scale,
  }
  panning.value = null
  selection.value = null
}

const handleCanvasPointerDown = (event: PointerEvent): void => {
  const target = event.target as HTMLElement | null
  if (
    target?.closest(
      '.workflow-node, .workflow-node-trace, .workflow-toolbar, .workflow-execution-bar, .workflow-palette, .workflow-inspector, .workflow-history, .workflow-context-menu, .workflow-mobile-nodes, .workflow-locate, .workflow-connections',
    )
  ) {
    return
  }

  activePointers.set(event.pointerId, { x: event.clientX, y: event.clientY })
  if (activePointers.size === 2) {
    event.preventDefault()
    beginPinch()
    return
  }
  hideContextMenu()
  selectedId.value = null
  configNodeId.value = null

  if (mode.value === 'drag' || (event.pointerType === 'mouse' && event.button === 1)) {
    panning.value = {
      pointerId: event.pointerId,
      clientX: event.clientX,
      clientY: event.clientY,
      offsetX: canvas.offsetX,
      offsetY: canvas.offsetY,
    }
    return
  }

  if (
    mode.value === 'select' &&
    (event.pointerType !== 'mouse' || event.button === 0) &&
    canvasRef.value
  ) {
    const rect = canvasRef.value.getBoundingClientRect()
    const x = (event.clientX - rect.left - canvas.offsetX) / canvas.scale
    const y = (event.clientY - rect.top - canvas.offsetY) / canvas.scale
    selection.value = { pointerId: event.pointerId, startX: x, startY: y, endX: x, endY: y }
    selectedId.value = null
  }
}

const handlePointerMove = (event: PointerEvent): void => {
  if (activePointers.has(event.pointerId)) {
    activePointers.set(event.pointerId, { x: event.clientX, y: event.clientY })
  }

  if (pinchState.value && activePointers.size >= 2 && canvasRef.value) {
    const points = [...activePointers.values()]
    const first = points[0]
    const second = points[1]
    if (!first || !second) return
    const distance = Math.hypot(second.x - first.x, second.y - first.y)
    if (distance < 1) return
    const rect = canvasRef.value.getBoundingClientRect()
    const middleX = (first.x + second.x) / 2
    const middleY = (first.y + second.y) / 2
    const nextScale = Math.max(
      0.25,
      Math.min(1.8, pinchState.value.scale * (distance / pinchState.value.distance)),
    )
    canvas.scale = nextScale
    canvas.offsetX = middleX - rect.left - pinchState.value.worldX * nextScale
    canvas.offsetY = middleY - rect.top - pinchState.value.worldY * nextScale
    event.preventDefault()
    return
  }

  if (draggingNode.value) {
    const current = draggingNode.value
    if (current.pointerId !== event.pointerId) return
    const deltaX = (event.clientX - current.clientX) / canvas.scale
    const deltaY = (event.clientY - current.clientY) / canvas.scale
    if (Math.abs(deltaX) > 1 || Math.abs(deltaY) > 1) {
      current.moved = true
      clearLongPress()
    }
    current.node.x = current.nodeX + deltaX
    current.node.y = current.nodeY + deltaY
    return
  }

  if (panning.value) {
    const current = panning.value
    if (current.pointerId !== event.pointerId) return
    canvas.offsetX = current.offsetX + event.clientX - current.clientX
    canvas.offsetY = current.offsetY + event.clientY - current.clientY
    return
  }

  if (selection.value && canvasRef.value) {
    if (selection.value.pointerId !== event.pointerId) return
    const rect = canvasRef.value.getBoundingClientRect()
    selection.value.endX = (event.clientX - rect.left - canvas.offsetX) / canvas.scale
    selection.value.endY = (event.clientY - rect.top - canvas.offsetY) / canvas.scale
    return
  }

  if (
    connecting.value &&
    connectingPointerId.value === event.pointerId &&
    tempConnection.value &&
    canvasRef.value
  ) {
    const rect = canvasRef.value.getBoundingClientRect()
    tempConnection.value = {
      ...tempConnection.value,
      x: (event.clientX - rect.left - canvas.offsetX) / canvas.scale,
      y: (event.clientY - rect.top - canvas.offsetY) / canvas.scale,
    }
  }
}

const handlePointerUp = (event: PointerEvent): void => {
  clearLongPress()
  activePointers.delete(event.pointerId)
  if (pinchState.value) {
    if (activePointers.size < 2) pinchState.value = null
    return
  }

  if (connecting.value && connectingPointerId.value === event.pointerId) {
    finishConnection(event)
  } else if (selection.value && selection.value.pointerId === event.pointerId) {
    const current = selection.value
    const left = Math.min(current.startX, current.endX)
    const right = Math.max(current.startX, current.endX)
    const top = Math.min(current.startY, current.endY)
    const bottom = Math.max(current.startY, current.endY)
    const matched = nodes.value.filter((node) => {
      const height = nodeHeights.value[node.id] ?? 104
      return !(
        node.x > right ||
        node.x + NODE_WIDTH < left ||
        node.y > bottom ||
        node.y + height < top
      )
    })
    selectedId.value = matched.length === 1 ? (matched[0]?.id ?? null) : null
    selection.value = null
  }
  if (draggingNode.value?.pointerId === event.pointerId) draggingNode.value = null
  if (panning.value?.pointerId === event.pointerId) panning.value = null
}

const handlePointerCancel = (event: PointerEvent): void => {
  clearLongPress()
  activePointers.delete(event.pointerId)
  if (pinchState.value && activePointers.size < 2) pinchState.value = null
  if (connectingPointerId.value === event.pointerId) {
    connecting.value = null
    connectingPointerId.value = null
    tempConnection.value = null
  }
  if (draggingNode.value?.pointerId === event.pointerId) draggingNode.value = null
  if (panning.value?.pointerId === event.pointerId) panning.value = null
  if (selection.value?.pointerId === event.pointerId) selection.value = null
}

const handleWheel = (event: WheelEvent): void => {
  if (!canvasRef.value) return
  const rect = canvasRef.value.getBoundingClientRect()
  const mouseX = event.clientX - rect.left
  const mouseY = event.clientY - rect.top
  const nextScale = Math.max(
    isMobile.value ? 0.25 : 0.35,
    Math.min(1.8, canvas.scale + (event.deltaY > 0 ? -0.08 : 0.08)),
  )
  if (nextScale === canvas.scale) return
  const worldX = (mouseX - canvas.offsetX) / canvas.scale
  const worldY = (mouseY - canvas.offsetY) / canvas.scale
  canvas.offsetX = mouseX - worldX * nextScale
  canvas.offsetY = mouseY - worldY * nextScale
  canvas.scale = nextScale
}

const locateInitialNode = (smooth = true): void => {
  if (!canvasRef.value) return
  const target = eventNode.value ?? nodes.value[0]
  if (!target) {
    canvas.offsetX = canvasRef.value.clientWidth / 2
    canvas.offsetY = canvasRef.value.clientHeight / 2
    canvas.scale = isMobile.value ? 0.82 : 1
    return
  }
  const height = nodeHeights.value[target.id] ?? 104
  const nextScale = isMobile.value ? 0.82 : 1
  canvas.scale = nextScale
  canvas.offsetX = canvasRef.value.clientWidth / 2 - (target.x + NODE_WIDTH / 2) * nextScale
  canvas.offsetY = canvasRef.value.clientHeight / 2 - (target.y + height / 2) * nextScale
  if (smooth) void nextTick(measureNodeHeights)
}

/**
 * 历史模式专用：把整张图（含节点下方的详情卡片）缩放到视口内。
 *
 * <p>快照里存的画布偏移是作者当时的视口，换台设备（尤其是手机）打开就会跑到视口外，
 * 所以历史模式不用它，直接按当前视口重新适配。
 */
const fitGraph = (): void => {
  const element = canvasRef.value
  if (!element || !nodes.value.length) return
  const rect = element.getBoundingClientRect()
  if (!rect.width || !rect.height) return

  const cardHeights: Record<string, number> = {}
  element.querySelectorAll<HTMLElement>('[data-trace-for]').forEach((item) => {
    const id = item.dataset.traceFor
    if (id) cardHeights[id] = item.offsetHeight
  })

  let minX = Number.POSITIVE_INFINITY
  let minY = Number.POSITIVE_INFINITY
  let maxX = Number.NEGATIVE_INFINITY
  let maxY = Number.NEGATIVE_INFINITY
  for (const node of nodes.value) {
    const height = nodeHeights.value[node.id] ?? NODE_FALLBACK_HEIGHT
    const measuredCard = cardHeights[node.id]
    const cardHeight = measuredCard ? measuredCard + 10 : 0
    minX = Math.min(minX, node.x)
    minY = Math.min(minY, node.y)
    maxX = Math.max(maxX, node.x + NODE_WIDTH)
    maxY = Math.max(maxY, node.y + height + cardHeight)
  }

  // 顶部要给工具栏和执行状态条让位，否则图会被压在它们下面
  const topInset = 180
  const padding = 56
  const usableWidth = Math.max(1, rect.width - padding * 2)
  const usableHeight = Math.max(1, rect.height - topInset - padding)
  const scale = Math.max(
    0.3,
    Math.min(
      1,
      usableWidth / Math.max(1, maxX - minX),
      usableHeight / Math.max(1, maxY - minY),
    ),
  )
  canvas.scale = scale
  canvas.offsetX = padding + usableWidth / 2 - ((minX + maxX) / 2) * scale
  canvas.offsetY = topInset + usableHeight / 2 - ((minY + maxY) / 2) * scale
}

const saveInspector = (updated: WorkflowNode): void => {
  const index = nodes.value.findIndex((node) => node.id === updated.id)
  if (index < 0) return
  const current = nodes.value[index]
  nodes.value[index] = {
    ...updated,
    x: current?.x ?? updated.x,
    y: current?.y ?? updated.y,
    descriptor: current?.descriptor ?? updated.descriptor,
  }
  if (!updated.branch) {
    edges.value = edges.value.filter(
      (edge) => !(edge.from === updated.id && edge.port === 'failure'),
    )
  }
  selectedId.value = null
  configNodeId.value = null
  void nextTick(measureNodeHeights)
}

const removeNode = (node: WorkflowNode): void => {
  nodes.value = nodes.value.filter((item) => item.id !== node.id)
  edges.value = edges.value.filter((edge) => edge.from !== node.id && edge.to !== node.id)
  if (selectedId.value === node.id) selectedId.value = null
  if (configNodeId.value === node.id) configNodeId.value = null
  hideContextMenu()
  void nextTick(measureNodeHeights)
}

const removeEdge = (edge: WorkflowEdge): void => {
  edges.value = edges.value.filter((item) => edgeIdentity(item) !== edgeIdentity(edge))
  hideContextMenu()
}

const clearCanvas = (): void => {
  nodes.value = []
  edges.value = []
  selectedId.value = null
  configNodeId.value = null
  hideContextMenu()
}

const openNodeContextMenu = (event: MouseEvent, node: WorkflowNode): void => {
  if (historyMode.value) return
  selectedId.value = node.id
  Object.assign(contextMenu, {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    kind: 'node',
    node,
    edge: undefined,
  })
}

const openEdgeContextMenu = (event: MouseEvent, edge: WorkflowEdge): void => {
  if (historyMode.value) return
  Object.assign(contextMenu, {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    kind: 'edge',
    node: undefined,
    edge,
  })
}

const openCanvasContextMenu = (event: MouseEvent): void => {
  if (historyMode.value) return
  const target = event.target as HTMLElement | null
  if (target?.closest('.workflow-node, .workflow-context-menu')) return
  Object.assign(contextMenu, {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    kind: 'canvas',
    node: undefined,
    edge: undefined,
  })
}

const hideContextMenu = (): void => {
  contextMenu.visible = false
}

const handleKeydown = (event: KeyboardEvent): void => {
  const target = event.target as HTMLElement | null
  if (target?.matches('input, textarea, select')) return
  if (event.key === 'Escape') {
    selectedId.value = null
    configNodeId.value = null
    historyOpen.value = false
    hideContextMenu()
  }
  if (event.key === 'Delete' && !historyMode.value && selectedNode.value) {
    removeNode(selectedNode.value)
  }
}

const handleDocumentClick = (event: MouseEvent): void => {
  if (!(event.target as HTMLElement | null)?.closest('.workflow-context-menu')) hideContextMenu()
}

const buildDefinition = (): WorkflowDefinition => ({
  eventNodeId: eventNode.value?.id ?? '',
  canvas: { ...canvas },
  nodes: nodes.value.map(({ descriptor: _descriptor, ...node }) => node),
  edges: edges.value,
})

const validateBeforeSave = (): boolean => {
  if (!name.value.trim()) {
    message.error('请输入工作流名称')
    return false
  }
  const adapterMissingConnection = nodes.value.find(
    (node) => isAdapterNode(node) && !node.connectionId,
  )
  if (adapterMissingConnection) {
    message.error(
      `适配器节点「${adapterMissingConnection.descriptor?.name || adapterMissingConnection.nodeKey}」还没有绑定连接`,
    )
    return false
  }
  for (const node of nodes.value) {
    const parameter = missingParameter(node)
    if (!parameter) continue
    message.error(
      `节点「${nodeLabel(node)}」的参数「${parameterLabel(parameter)}」没有配置引用或默认值`,
    )
    return false
  }
  return true
}

const save = async (): Promise<boolean> => {
  if (!validateBeforeSave()) return false
  saving.value = true
  try {
    const result = await saveWorkflow({
      id: workflowId.value,
      name: name.value.trim(),
      description: description.value,
      definition: buildDefinition(),
    })
    workflowId.value = result.workflowId
    message.success(`已保存，当前版本 ${result.versionNo}`)
    if (route.params.id !== result.workflowId) {
      await router.replace(`/workflow/edit/${result.workflowId}`)
    }
    return true
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '保存失败')
    return false
  } finally {
    saving.value = false
  }
}

const saveAndRun = async (): Promise<void> => {
  if (!(await save()) || !workflowId.value) return
  try {
    testResult.value = await testWorkflow(workflowId.value)
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '执行失败')
  }
}

const toggleEnabled = async (): Promise<void> => {
  if (!workflowId.value) {
    enabled.value = !enabled.value
    return
  }
  const next = !enabled.value
  try {
    await setWorkflowEnabled(workflowId.value, next)
    enabled.value = next
    message.success(next ? '工作流已启用' : '工作流已停用')
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '状态切换失败')
  }
}

const openHistory = (): void => {
  if (!workflowId.value) return
  selectedId.value = null
  configNodeId.value = null
  historyOpen.value = true
}

/** 选中一条执行记录：抽屉关掉，画布换成那次执行的定义快照并进入只读。 */
const openExecution = async (execution: WorkflowExecutionSummary): Promise<void> => {
  // 先记住编辑态（含面板开合），退出时原样还原，不丢未保存的改动
  editingSnapshot = {
    nodes: nodes.value,
    edges: edges.value,
    canvas: { ...canvas },
    paletteOpen: paletteOpen.value,
  }
  historyOpen.value = false
  selectedId.value = null
  configNodeId.value = null
  paletteOpen.value = false
  loadingExecution.value = true
  executionSnapshotMissing.value = false
  executionView.value = execution
  executionTrace.value = null
  try {
    const [detail, snapshot] = await Promise.all([
      getExecution(execution.executionId),
      getExecutionDefinition(execution.executionId),
    ])
    executionTrace.value = detail
    const definition = snapshot.definition
    if (definition) {
      nodes.value = (definition.nodes ?? []).map((node) =>
        attachDescriptor(node, allCatalog.value),
      )
      edges.value = definition.edges ?? []
      if (definition.canvas) Object.assign(canvas, definition.canvas)
    } else {
      executionSnapshotMissing.value = true
    }
    // 先把 loading 放掉，详情卡片才会渲染出来，fitGraph 才量得到它们的高度
    loadingExecution.value = false
    await nextTick()
    measureNodeHeights()
    // 详情卡片渲染完再量高度，按当前视口把整张图放进来
    await nextTick()
    fitGraph()
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '执行日志加载失败')
    exitExecutionView()
  } finally {
    loadingExecution.value = false
  }
}

/** 退出历史模式：还原进历史模式前的编辑态。 */
const exitExecutionView = (): void => {
  executionView.value = null
  executionTrace.value = null
  executionSnapshotMissing.value = false
  if (!editingSnapshot) return
  nodes.value = editingSnapshot.nodes
  edges.value = editingSnapshot.edges
  Object.assign(canvas, editingSnapshot.canvas)
  paletteOpen.value = editingSnapshot.paletteOpen
  editingSnapshot = null
  void nextTick(measureNodeHeights)
}

function isEventNodeFromCatalog(item: WorkflowNodeCatalogItem): boolean {
  return item.nodeType === 'EVENT'
}

watch(nodes, () => void nextTick(measureNodeHeights), { deep: true })

const syncViewportMode = (): void => {
  const next = window.matchMedia('(max-width: 56rem)').matches
  if (next === isMobile.value) return
  isMobile.value = next
  paletteOpen.value = !next
  // 横竖屏切换后视口差很多，历史模式重新适配一次
  if (historyMode.value) void nextTick(fitGraph)
}

onMounted(async () => {
  syncViewportMode()
  await Promise.all([loadCatalog(), loadConnections()])
  await loadWorkflow()
  loading.value = false
  await nextTick()
  measureNodeHeights()
  if (!workflowId.value && !nodes.value.length) locateInitialNode(false)
  window.addEventListener('resize', syncViewportMode)
  window.addEventListener('pointermove', handlePointerMove)
  window.addEventListener('pointerup', handlePointerUp)
  window.addEventListener('pointercancel', handlePointerCancel)
  window.addEventListener('keydown', handleKeydown)
  window.addEventListener('click', handleDocumentClick)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncViewportMode)
  window.removeEventListener('pointermove', handlePointerMove)
  window.removeEventListener('pointerup', handlePointerUp)
  window.removeEventListener('pointercancel', handlePointerCancel)
  window.removeEventListener('keydown', handleKeydown)
  window.removeEventListener('click', handleDocumentClick)
})
</script>

<template>
  <section
    ref="canvasRef"
    class="workflow-studio"
    :class="{ 'is-selecting': mode === 'select', 'is-panning': Boolean(panning) }"
    :style="canvasBackgroundStyle"
    @pointerdown="handleCanvasPointerDown"
    @wheel.prevent="handleWheel"
    @dragover.prevent
    @drop="handleDrop"
    @contextmenu.prevent="openCanvasContextMenu"
  >
    <header class="workflow-toolbar">
      <div class="workflow-toolbar__left">
        <button
          type="button"
          class="workflow-toolbar__close"
          aria-label="退出工作流编辑器"
          @click="router.push('/workflow/list')"
        >
          <X :size="19" />
        </button>
        <input
          v-if="editingName"
          v-model="name"
          class="workflow-toolbar__name-input"
          autofocus
          @blur="saveWorkflowName"
          @keyup.enter="saveWorkflowName"
        />
        <button v-else type="button" class="workflow-toolbar__name" @dblclick="editingName = true">
          {{ name || '新建工作流' }}
        </button>
      </div>

      <div class="workflow-toolbar__modes" role="group" aria-label="画布模式">
        <button
          type="button"
          :class="{ 'is-active': mode === 'drag' }"
          title="拖动模式"
          aria-label="拖动模式"
          @click="toggleMode('drag')"
        >
          <Hand :size="17" />
        </button>
        <button
          type="button"
          :class="{ 'is-active': mode === 'select' }"
          title="选取模式"
          aria-label="选取模式"
          @click="toggleMode('select')"
        >
          <MousePointer2 :size="17" />
        </button>
      </div>

      <div class="workflow-toolbar__actions">
        <template v-if="!historyMode">
          <BaseButton
            :appearance="enabled ? 'success' : 'danger'"
            size="small"
            @click="toggleEnabled"
          >
            <Power :size="14" />
            {{ enabled ? '已启用' : '已停用' }}
          </BaseButton>
          <BaseButton
            size="small"
            :disabled="saving || !name.trim() || !nodes.length || Boolean(eventNode)"
            :loading="saving"
            :title="eventNode ? '包含事件节点的工作流只能保存，不能直接测试' : ''"
            @click="saveAndRun"
          >
            <Play :size="14" />
            保存并测试
          </BaseButton>
          <BaseButton
            appearance="success"
            size="small"
            :disabled="saving || !name.trim() || !nodes.length"
            :loading="saving"
            @click="save"
          >
            <Save :size="14" />
            保存
          </BaseButton>
          <BaseButton v-if="workflowId" appearance="warning" size="small" @click="openHistory">
            <History :size="14" />
            历史日志
          </BaseButton>
        </template>
      </div>
    </header>

    <!-- 历史日志模式的状态条：只读，退出按钮也放这里 -->
    <div
      v-if="historyMode"
      class="workflow-execution-bar"
      :class="{ 'is-failed': executionView?.status !== 'SUCCESS' }"
    >
      <span class="workflow-execution-bar__status">
        {{ statusLabel(executionView?.status) }}
      </span>
      <span class="workflow-execution-bar__item">
        {{ triggerLabel(executionView?.triggerType) }}
      </span>
      <span class="workflow-execution-bar__item">{{ formatTime(executionView?.startTime) }}</span>
      <span class="workflow-execution-bar__item">
        耗时 {{ formatDuration(executionView?.durationMs) }}
      </span>
      <span class="workflow-execution-bar__item">
        节点 {{ executedNodeCount }}/{{ nodes.length }}
      </span>
      <span class="workflow-execution-bar__item">v{{ executionView?.definitionVersion ?? '—' }}</span>
      <span v-if="loadingExecution" class="workflow-execution-bar__item">正在加载执行详情…</span>
      <span v-if="executionSnapshotMissing" class="workflow-execution-bar__warn">
        当时那份定义已不存在，按当前版本显示
      </span>
      <BaseButton appearance="secondary" size="small" @click="exitExecutionView">
        <X :size="14" />
        退出历史模式
      </BaseButton>
    </div>

    <WorkflowPalette
      v-if="paletteOpen && !historyMode"
      :catalog="catalog"
      :connections="connections"
      :locked-adapter-plugin-version-id="lockedAdapterPluginVersionId"
      :loading="loadingCatalog"
      @start-drag="startPaletteDrag"
      @end-drag="endPaletteDrag"
      @add="addNodeAtCenter"
      @close="paletteOpen = false"
    />

    <div ref="worldRef" class="workflow-world" :style="canvasWorldStyle">
      <WorkflowConnections
        :nodes="nodes"
        :edges="edges"
        :node-heights="nodeHeights"
        :temp-connection="tempConnection"
        @contextmenu="openEdgeContextMenu"
      />

      <template v-for="node in nodes" :key="node.id">
        <WorkflowNodeCard
          :node="node"
          :selected="selectedId === node.id"
          :connecting="connecting?.node.id === node.id"
          :readonly="historyMode"
          :status="historyMode ? executionStatusOf(node.id) : null"
          @select="selectedId = $event.id"
          @move-start="startNodeMove"
          @connect-start="startConnection"
          @contextmenu="openNodeContextMenu"
          @open-config="openNodeConfig"
        />
        <!-- 历史日志模式：只有执行到的节点才挂输入输出预览；没走到的节点靠灰色边框表示 -->
        <div
          v-if="historyMode && !loadingExecution && traceByNodeId[node.id]"
          class="workflow-node-trace"
          :data-trace-for="node.id"
          :style="{
            left: `${node.x}px`,
            top: `${node.y + (nodeHeights[node.id] ?? NODE_FALLBACK_HEIGHT) + 10}px`,
          }"
        >
          <WorkflowNodeTraceCard
            :trace="traceByNodeId[node.id] ?? null"
            :execution-id="executionView?.executionId ?? ''"
            :show-name="false"
            :input-collapsed="false"
            hint="点击查看"
            compact
          />
        </div>
      </template>

      <div v-if="selection" class="workflow-selection" :style="selectionStyle" />
    </div>

    <div v-if="loading" class="workflow-studio__state">正在加载工作流…</div>
    <div v-else-if="!nodes.length" class="workflow-studio__state workflow-studio__state--empty">
      <strong>工作流画布</strong>
      <span>从左侧拖入或点击节点开始编排</span>
    </div>

    <button
      v-if="!historyMode"
      type="button"
      class="workflow-mobile-nodes"
      :class="{
        'is-active': paletteOpen,
        'is-hidden': isMobile && Boolean(configNode),
      }"
      aria-label="节点面板"
      @click="paletteOpen = !paletteOpen"
    >
      <Blocks :size="18" />
      <span>节点</span>
    </button>

    <button
      type="button"
      class="workflow-locate"
      :class="{
        'is-shifted': Boolean(configNode),
        'is-mobile-hidden': isMobile && (paletteOpen || historyOpen || Boolean(configNode)),
      }"
      title="定位初始节点"
      aria-label="定位初始节点"
      @click="locateInitialNode()"
    >
      <LocateFixed :size="19" />
    </button>

    <WorkflowInspector
      v-if="configNode"
      :node="configNode"
      :connections="connections"
      :all-nodes="nodes"
      :all-edges="edges"
      @close="configNodeId = null"
      @save="saveInspector"
      @remove="removeNode"
    />

    <WorkflowHistoryPanel
      v-if="historyOpen && workflowId"
      :workflow-id="workflowId"
      @close="historyOpen = false"
      @select="openExecution"
    />

    <WorkflowTestResultDialog
      v-if="testResult"
      :result="testResult"
      :nodes="nodes"
      @close="testResult = null"
    />

    <div
      v-if="contextMenu.visible"
      class="workflow-context-menu"
      :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
    >
      <button
        v-if="contextMenu.kind === 'node' && contextMenu.node"
        type="button"
        @click="removeNode(contextMenu.node)"
      >
        删除节点
      </button>
      <button
        v-if="contextMenu.kind === 'edge' && contextMenu.edge"
        type="button"
        @click="removeEdge(contextMenu.edge)"
      >
        删除连线
      </button>
      <button v-if="contextMenu.kind === 'canvas'" type="button" @click="clearCanvas">
        清空画布
      </button>
    </div>
  </section>
</template>

<style scoped>
.workflow-studio {
  position: fixed;
  z-index: 1;
  inset: 0;
  overflow: hidden;
  background-color: var(--sys-color-canvas);
  background-image: radial-gradient(
    color-mix(in srgb, var(--sys-color-text-muted) 32%, transparent) 1.2px,
    transparent 1.2px
  );
  cursor: grab;
  touch-action: none;
}

.workflow-studio.is-panning {
  cursor: grabbing;
}

.workflow-studio.is-selecting {
  cursor: crosshair;
}

.workflow-toolbar {
  position: absolute;
  z-index: 50;
  inset-block-start: 1rem;
  inset-inline: 1rem;
  display: grid;
  min-block-size: 4.25rem;
  grid-template-columns: minmax(12rem, 1fr) auto minmax(24rem, 1fr);
  align-items: center;
  border: 1px solid color-mix(in srgb, var(--sys-color-border) 76%, transparent);
  border-radius: 0.75rem;
  background: color-mix(in srgb, var(--sys-color-surface-raised) 94%, transparent);
  box-shadow: var(--sys-shadow-raised);
  backdrop-filter: blur(1rem);
  gap: var(--sys-space-4);
  padding: var(--sys-space-3) var(--sys-space-4);
}

.workflow-toolbar__left {
  display: flex;
  min-inline-size: 0;
  align-items: center;
  gap: var(--sys-space-3);
}

.workflow-toolbar__close {
  display: grid;
  inline-size: 2.5rem;
  block-size: 2.5rem;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--sys-color-border);
  border-radius: 50%;
  background: var(--sys-color-field);
  color: var(--sys-color-text-muted);
  cursor: pointer;
}

.workflow-toolbar__close:hover {
  border-color: var(--sys-color-danger-border);
  background: var(--sys-color-danger-subtle);
  color: var(--sys-color-danger-text);
}

.workflow-mobile-nodes {
  display: none;
  position: absolute;
  z-index: 25;
  inset-block-end: 1.5rem;
  inset-inline-start: 1.5rem;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--sys-color-border);
  border-radius: 999px;
  background: var(--sys-color-surface-raised);
  box-shadow: var(--sys-shadow-raised);
  color: var(--sys-color-text-muted);
  cursor: pointer;
  gap: var(--sys-space-2);
  padding: 0.65rem 0.9rem;
}

.workflow-mobile-nodes.is-active {
  border-color: var(--sys-color-action-primary);
  background: var(--sys-color-action-primary);
  color: var(--sys-color-on-action-primary);
}

.workflow-mobile-nodes span {
  font: var(--sys-typography-label-strong);
}

.workflow-toolbar__name,
.workflow-toolbar__name-input {
  min-inline-size: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--sys-color-text);
  font: 750 1.05rem/1.3 var(--ref-font-sans);
}

.workflow-toolbar__name {
  overflow: hidden;
  cursor: text;
  padding: 0;
  text-align: start;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-toolbar__name-input {
  inline-size: min(20rem, 100%);
  border-block-end: 1px solid var(--sys-color-action-primary);
  padding-block-end: 0.25rem;
}

.workflow-toolbar__modes {
  display: inline-flex;
  border: 1px solid var(--sys-color-border-strong);
  border-radius: 0.65rem;
  background: var(--sys-color-surface-muted);
  padding: 0.18rem;
}

.workflow-toolbar__modes button {
  display: grid;
  inline-size: 2.2rem;
  block-size: 2rem;
  place-items: center;
  border: 0;
  border-radius: 0.5rem;
  background: transparent;
  color: var(--sys-color-text-muted);
  cursor: pointer;
}

.workflow-toolbar__modes button.is-active {
  background: var(--sys-color-action-primary);
  color: var(--sys-color-on-action-primary);
}

.workflow-toolbar__actions {
  display: flex;
  min-inline-size: 0;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sys-space-2);
}

.workflow-toolbar__actions :deep(.base-button) {
  white-space: nowrap;
}

.workflow-execution-bar {
  position: absolute;
  z-index: 40;
  inset-block-start: 5.5rem;
  inset-inline: 1rem;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sys-space-3);
  border: 1px solid var(--sys-color-border);
  border-radius: 0.65rem;
  background: color-mix(in srgb, var(--sys-color-surface-raised) 97%, transparent);
  box-shadow: var(--sys-shadow-raised);
  padding: 0.5rem 0.75rem;
}

.workflow-execution-bar__status {
  flex: none;
  border-radius: 999px;
  background: var(--sys-color-success-subtle);
  color: var(--sys-color-success-text);
  font: var(--sys-typography-label);
  padding: 0.1rem 0.55rem;
}

.workflow-execution-bar.is-failed .workflow-execution-bar__status {
  background: var(--sys-color-danger-subtle);
  color: var(--sys-color-danger-text);
}

.workflow-execution-bar__item {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-execution-bar__warn {
  color: var(--sys-color-warning-text);
  font: var(--sys-typography-caption);
}

.workflow-execution-bar :deep(.base-button) {
  margin-inline-start: auto;
}

/* 节点下方的执行详情预览；跟着画布一起平移缩放 */
.workflow-node-trace {
  position: absolute;
  z-index: 15;
  inline-size: 15.625rem;
}

.workflow-world {
  position: absolute;
  z-index: 10;
  inset-block-start: 0;
  inset-inline-start: 0;
  inline-size: 0;
  block-size: 0;
  transform-origin: 0 0;
}

.workflow-selection {
  position: absolute;
  z-index: 100;
  border: 1px solid var(--sys-color-action-primary);
  background: color-mix(in srgb, var(--sys-color-action-primary) 14%, transparent);
  pointer-events: none;
}

.workflow-studio__state {
  position: absolute;
  z-index: 5;
  inset-block-start: 50%;
  inset-inline-start: 50%;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-body-compact);
  pointer-events: none;
  transform: translate(-50%, -50%);
}

.workflow-studio__state--empty {
  display: grid;
  justify-items: center;
  gap: var(--sys-space-2);
}

.workflow-studio__state--empty strong {
  color: var(--sys-color-text);
  font: var(--sys-typography-label-strong);
}

.workflow-locate {
  position: absolute;
  z-index: 35;
  inset-block-end: 1.5rem;
  inset-inline-end: 1.5rem;
  display: grid;
  inline-size: 2.75rem;
  block-size: 2.75rem;
  place-items: center;
  border: 1px solid var(--sys-color-border);
  border-radius: 50%;
  background: var(--sys-color-surface-raised);
  box-shadow: var(--sys-shadow-raised);
  color: var(--sys-color-text-muted);
  cursor: pointer;
  transition:
    inset-inline-end var(--sys-motion-normal),
    transform var(--sys-motion-fast);
}

.workflow-locate:hover {
  color: var(--sys-color-action-primary);
  transform: scale(1.06);
}

.workflow-locate.is-shifted {
  inset-inline-end: 25rem;
}

.workflow-context-menu {
  position: fixed;
  z-index: 1001;
  display: grid;
  min-inline-size: 8rem;
  overflow: hidden;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.6rem;
  background: var(--sys-color-surface-raised);
  box-shadow: var(--sys-shadow-raised);
  padding: 0.25rem;
}

.workflow-context-menu button {
  border: 0;
  border-radius: 0.45rem;
  background: transparent;
  color: var(--sys-color-text);
  cursor: pointer;
  font: var(--sys-typography-body-compact);
  padding: 0.55rem 0.75rem;
  text-align: start;
}

.workflow-context-menu button:hover {
  background: var(--sys-color-danger-subtle);
  color: var(--sys-color-danger-text);
}

@media (max-width: 72rem) {
  .workflow-toolbar {
    grid-template-columns: minmax(9rem, 1fr) auto minmax(0, 1fr);
  }

  .workflow-toolbar__actions :deep(.base-button) {
    padding-inline: var(--sys-space-2);
  }
}

@media (max-width: 56rem) {
  .workflow-toolbar {
    inset-block-start: 0.75rem;
    inset-inline: 0.75rem;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: var(--sys-space-2);
  }

  .workflow-toolbar__modes {
    display: none;
  }

  .workflow-mobile-nodes {
    display: inline-flex;
  }

  .workflow-mobile-nodes.is-hidden {
    display: none;
  }

  .workflow-toolbar__actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
    overflow-x: auto;
    padding-block-end: 0.1rem;
  }

  .workflow-locate {
    inset-block-end: 1rem;
    inset-inline-end: 1rem;
  }

  .workflow-locate.is-shifted {
    display: none;
  }

  .workflow-locate.is-mobile-hidden {
    display: none;
  }
}
</style>
