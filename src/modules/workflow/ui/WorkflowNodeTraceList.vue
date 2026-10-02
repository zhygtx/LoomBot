<script setup lang="ts">
import type { WorkflowTraceNode } from '../api/workflow-api'
import WorkflowNodeTraceCard from './WorkflowNodeTraceCard.vue'

const props = defineProps<{
  nodes: WorkflowTraceNode[]
  /** 按节点 id 再解析一层展示名（测试弹窗用画布里的中文名）；不传就用日志里的快照名。 */
  labelOf?: (node: WorkflowTraceNode) => string | undefined
  /** 最终节点 id：命中时打「最终节点」标记，只用于测试执行。 */
  terminalIds?: string[]
  /** 执行 id：大内容要按它去后端取正文。 */
  executionId?: string
}>()

function nodeName(node: WorkflowTraceNode): string {
  return props.labelOf?.(node) || node.name?.trim() || node.nodeKey || node.nodeId || '未命名节点'
}

function isTerminal(node: WorkflowTraceNode): boolean {
  return Boolean(node.nodeId && props.terminalIds?.includes(node.nodeId))
}
</script>

<template>
  <div class="workflow-trace">
    <WorkflowNodeTraceCard
      v-for="node in nodes"
      :key="node.nodeId ?? node.nodeKey"
      :trace="node"
      :name="nodeName(node)"
      :terminal="isTerminal(node)"
      :execution-id="executionId ?? ''"
    />
    <p v-if="!nodes.length" class="workflow-trace__empty">这次执行没有节点记录</p>
  </div>
</template>

<style scoped>
.workflow-trace {
  display: grid;
  gap: var(--sys-space-2);
}

.workflow-trace__empty {
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  text-align: center;
}
</style>
