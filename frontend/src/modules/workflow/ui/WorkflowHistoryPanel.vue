<script setup lang="ts">
import { X } from '@lucide/vue'

import type { WorkflowExecutionSummary } from '../api/workflow-api'
import WorkflowExecutionLogPanel from './WorkflowExecutionLogPanel.vue'

defineProps<{
  /** 当前工作流：抽屉里只查这一条工作流的执行记录。 */
  workflowId: string
}>()

defineEmits<{
  close: []
  select: [execution: WorkflowExecutionSummary]
}>()
</script>

<template>
  <aside class="workflow-history" @wheel.stop>
    <header class="workflow-history__header">
      <div>
        <strong>执行日志</strong>
        <small>保留最近 7 天</small>
      </div>
      <button type="button" aria-label="关闭执行日志" @click="$emit('close')">
        <X :size="18" />
      </button>
    </header>

    <div class="workflow-history__body">
      <WorkflowExecutionLogPanel
        :workflow-id="workflowId"
        default-include-test
        embedded
        selection-mode="emit"
        @select="$emit('select', $event)"
      />
    </div>
  </aside>
</template>

<style scoped>
.workflow-history {
  position: absolute;
  z-index: 45;
  inset-block: 6.25rem 1rem;
  inset-inline-end: 1rem;
  display: flex;
  inline-size: min(32rem, calc(100vw - 2rem));
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.75rem;
  background: color-mix(in srgb, var(--sys-color-surface-raised) 97%, transparent);
  box-shadow: var(--sys-shadow-raised);
}

.workflow-history__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  border-block-end: 1px solid var(--sys-color-border);
  gap: var(--sys-space-3);
  padding: var(--sys-space-4);
}

.workflow-history__header strong,
.workflow-history__header small {
  display: block;
}

.workflow-history__header strong {
  font: var(--sys-typography-label-strong);
}

.workflow-history__header small {
  margin-block-start: 0.2rem;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-history__header button {
  display: grid;
  inline-size: 2rem;
  block-size: 2rem;
  flex: none;
  place-items: center;
  border: 1px solid var(--sys-color-border);
  border-radius: 50%;
  background: var(--sys-color-field);
  color: var(--sys-color-text-muted);
  cursor: pointer;
}

.workflow-history__body {
  display: grid;
  min-block-size: 0;
  flex: 1;
  overflow-y: auto;
}

@media (max-width: 56rem) {
  .workflow-history {
    inset-block: auto 0.75rem;
    inset-inline: 0.75rem;
    inline-size: auto;
    max-block-size: min(32rem, calc(100dvh - 6rem));
  }
}
</style>
