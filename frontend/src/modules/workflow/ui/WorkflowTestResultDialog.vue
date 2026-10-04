<script setup lang="ts">
import { X } from '@lucide/vue'

import { BaseButton } from '@shared/ui'

import type { WorkflowNode, WorkflowTestResult, WorkflowTraceNode } from '../api/workflow-api'
import { statusLabel } from '../model/format'
import { nodeLabel } from '../model/graph'
import WorkflowNodeTraceList from './WorkflowNodeTraceList.vue'

const props = defineProps<{
  result: WorkflowTestResult
  nodes: WorkflowNode[]
}>()

defineEmits<{
  close: []
}>()

/** 画布上的中文节点名优先于日志里的快照名。 */
function labelOf(node: WorkflowTraceNode): string | undefined {
  const match = props.nodes.find((item) => item.id === node.nodeId)
  return match ? nodeLabel(match) : undefined
}
</script>

<template>
  <div class="workflow-test-result" @wheel.stop @click.self="$emit('close')">
    <section class="workflow-test-result__dialog" role="dialog" aria-modal="true">
      <header class="workflow-test-result__header">
        <div>
          <strong>测试执行结果</strong>
          <small>{{ result.executionId }}</small>
        </div>
        <button type="button" aria-label="关闭测试结果" @click="$emit('close')">
          <X :size="18" />
        </button>
      </header>

      <div class="workflow-test-result__summary">
        <span
          class="workflow-test-result__status"
          :class="{
            'is-success': result.status === 'SUCCESS',
            'is-failed': result.status !== 'SUCCESS',
          }"
        >
          {{ statusLabel(result.status) }}
        </span>
        <span>{{ result.durationMs }} ms</span>
        <span>{{ result.nodes.length }} 个节点</span>
      </div>
      <p v-if="result.errorMessage" class="workflow-test-result__summary-error">
        {{ result.errorMessage }}
      </p>

      <div class="workflow-test-result__body">
        <section class="workflow-test-result__section">
          <h3>节点执行结果</h3>
          <WorkflowNodeTraceList
            :nodes="result.nodes"
            :label-of="labelOf"
            :terminal-ids="result.terminalNodeIds"
            :execution-id="result.executionId"
          />
        </section>
      </div>

      <footer class="workflow-test-result__footer">
        <BaseButton size="small" @click="$emit('close')">关闭</BaseButton>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.workflow-test-result {
  position: fixed;
  z-index: 1200;
  inset: 0;
  display: grid;
  place-items: center;
  background: color-mix(in srgb, var(--sys-color-text) 28%, transparent);
  padding: var(--sys-space-4);
}

.workflow-test-result__dialog {
  display: flex;
  inline-size: min(52rem, 100%);
  max-block-size: min(48rem, calc(100dvh - 2rem));
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.85rem;
  background: var(--sys-color-surface-raised);
  box-shadow: var(--sys-shadow-raised);
}

.workflow-test-result__header,
.workflow-test-result__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-block-end: 1px solid var(--sys-color-border);
  gap: var(--sys-space-3);
  padding: var(--sys-space-4);
}

.workflow-test-result__header strong,
.workflow-test-result__header small {
  display: block;
}

.workflow-test-result__header strong {
  font: var(--sys-typography-label-strong);
}

.workflow-test-result__header small {
  margin-block-start: 0.2rem;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-test-result__header button {
  display: grid;
  inline-size: 2rem;
  block-size: 2rem;
  place-items: center;
  border: 1px solid var(--sys-color-border);
  border-radius: 50%;
  background: var(--sys-color-field);
  color: var(--sys-color-text-muted);
  cursor: pointer;
}

.workflow-test-result__summary {
  display: flex;
  align-items: center;
  gap: var(--sys-space-4);
  border-block-end: 1px solid var(--sys-color-border);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  padding: var(--sys-space-3) var(--sys-space-4);
}

.workflow-test-result__status {
  border-radius: 999px;
  background: var(--sys-color-danger-subtle);
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-label);
  padding: 0.15rem 0.55rem;
}

.workflow-test-result__status.is-success {
  background: var(--sys-color-success-subtle);
  color: var(--sys-color-success-text);
}

.workflow-test-result__summary-error {
  margin: 0;
  border-block-end: 1px solid var(--sys-color-danger-border);
  background: var(--sys-color-danger-subtle);
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-caption);
  padding: var(--sys-space-2) var(--sys-space-4);
}

.workflow-test-result__body {
  display: grid;
  min-block-size: 0;
  flex: 1;
  align-content: start;
  gap: var(--sys-space-4);
  overflow-y: auto;
  padding: var(--sys-space-4);
}

.workflow-test-result__section {
  display: grid;
  gap: var(--sys-space-2);
}

.workflow-test-result__section h3 {
  margin: 0;
  font: var(--sys-typography-label-strong);
}

.workflow-test-result__footer {
  justify-content: flex-end;
  border-block-start: 1px solid var(--sys-color-border);
  border-block-end: 0;
}

@media (max-width: 56rem) {
  .workflow-test-result {
    align-items: end;
    padding: 0;
  }

  .workflow-test-result__dialog {
    inline-size: 100%;
    max-block-size: 88dvh;
    border-radius: 1rem 1rem 0 0;
  }
}
</style>
