<script setup lang="ts">
import { computed } from 'vue'

import type { WorkflowEdge, WorkflowNode } from '../api/workflow-api'
import { NODE_FALLBACK_HEIGHT, NODE_WIDTH } from '../model/graph'

export interface TempConnection {
  from: string
  port: 'success' | 'failure'
  x: number
  y: number
}

const props = defineProps<{
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  nodeHeights: Record<string, number>
  tempConnection?: TempConnection | null
}>()

defineEmits<{
  select: [edge: WorkflowEdge]
  contextmenu: [event: MouseEvent, edge: WorkflowEdge]
}>()

const heightOf = (nodeId: string): number => props.nodeHeights[nodeId] ?? NODE_FALLBACK_HEIGHT

const portPoint = (nodeId: string, port: 'left' | 'success' | 'failure') => {
  const node = props.nodes.find((item) => item.id === nodeId)
  if (!node) return { x: 0, y: 0 }
  const height = heightOf(nodeId)
  if (port === 'left') return { x: node.x, y: node.y + height / 2 }
  if (port === 'failure') return { x: node.x + NODE_WIDTH, y: node.y + height * 0.74 }
  return {
    x: node.x + NODE_WIDTH,
    y: node.y + (node.branch ? height * 0.36 : height / 2),
  }
}

const bezier = (fromX: number, fromY: number, toX: number, toY: number): string => {
  const offset = Math.max(70, Math.abs(toX - fromX) * 0.48)
  return `M ${fromX} ${fromY} C ${fromX + offset} ${fromY}, ${toX - offset} ${toY}, ${toX} ${toY}`
}

const bounds = computed(() => {
  if (!props.nodes.length) return { minX: 0, minY: 0, width: 1200, height: 800 }
  let minX = Number.POSITIVE_INFINITY
  let minY = Number.POSITIVE_INFINITY
  let maxX = Number.NEGATIVE_INFINITY
  let maxY = Number.NEGATIVE_INFINITY
  for (const node of props.nodes) {
    minX = Math.min(minX, node.x)
    minY = Math.min(minY, node.y)
    maxX = Math.max(maxX, node.x + NODE_WIDTH)
    maxY = Math.max(maxY, node.y + heightOf(node.id))
  }
  return {
    minX: minX - 160,
    minY: minY - 160,
    width: Math.max(maxX - minX + 320, 1),
    height: Math.max(maxY - minY + 320, 1),
  }
})

const paths = computed(() =>
  props.edges.map((edge) => {
    const from = portPoint(edge.from, edge.port ?? 'success')
    const to = portPoint(edge.to, 'left')
    return {
      edge,
      port: edge.port ?? 'success',
      path: bezier(
        from.x - bounds.value.minX,
        from.y - bounds.value.minY,
        to.x - bounds.value.minX,
        to.y - bounds.value.minY,
      ),
    }
  }),
)

const tempPath = computed(() => {
  const temp = props.tempConnection
  if (!temp) return ''
  const from = portPoint(temp.from, temp.port)
  return bezier(
    from.x - bounds.value.minX,
    from.y - bounds.value.minY,
    temp.x - bounds.value.minX,
    temp.y - bounds.value.minY,
  )
})
</script>

<template>
  <svg
    class="workflow-connections"
    :width="bounds.width"
    :height="bounds.height"
    :viewBox="`0 0 ${bounds.width} ${bounds.height}`"
    :style="{
      left: `${bounds.minX}px`,
      top: `${bounds.minY}px`,
      width: `${bounds.width}px`,
      height: `${bounds.height}px`,
    }"
  >
    <defs>
      <marker
        id="workflow-arrow-success"
        markerWidth="9"
        markerHeight="7"
        refX="8"
        refY="3.5"
        orient="auto"
      >
        <path d="M0 0 L9 3.5 L0 7 Z" class="workflow-connections__arrow--success" />
      </marker>
      <marker
        id="workflow-arrow-failure"
        markerWidth="9"
        markerHeight="7"
        refX="8"
        refY="3.5"
        orient="auto"
      >
        <path d="M0 0 L9 3.5 L0 7 Z" class="workflow-connections__arrow--failure" />
      </marker>
    </defs>

    <path
      v-for="item in paths"
      :key="`hit-${item.edge.from}:${item.edge.to}:${item.port}`"
      :d="item.path"
      class="workflow-connections__hit"
      @click.stop="$emit('select', item.edge)"
      @contextmenu.prevent.stop="$emit('contextmenu', $event, item.edge)"
    />

    <path
      v-for="item in paths"
      :key="`line-${item.edge.from}:${item.edge.to}:${item.port}`"
      :d="item.path"
      class="workflow-connections__line"
      :class="item.port === 'failure' ? 'is-failure' : 'is-success'"
      :marker-end="
        item.port === 'failure' ? 'url(#workflow-arrow-failure)' : 'url(#workflow-arrow-success)'
      "
    />

    <path v-if="tempPath" :d="tempPath" class="workflow-connections__temp" />
  </svg>
</template>

<style scoped>
.workflow-connections {
  position: absolute;
  z-index: 10;
  max-inline-size: none;
  overflow: visible;
  pointer-events: none;
}

.workflow-connections__line,
.workflow-connections__temp,
.workflow-connections__hit {
  fill: none;
}

.workflow-connections__line {
  stroke-width: 2;
}

.workflow-connections__line.is-success {
  stroke: var(--sys-color-success-text);
}

.workflow-connections__line.is-failure {
  stroke: var(--sys-color-danger);
}

.workflow-connections__temp {
  stroke: var(--sys-color-action-primary);
  stroke-width: 2;
  stroke-dasharray: 5 5;
}

.workflow-connections__hit {
  stroke: transparent;
  stroke-width: 18;
  cursor: pointer;
  pointer-events: stroke;
}

.workflow-connections__arrow--success {
  fill: var(--sys-color-success-text);
}

.workflow-connections__arrow--failure {
  fill: var(--sys-color-danger);
}
</style>
