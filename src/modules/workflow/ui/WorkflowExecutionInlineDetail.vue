<script setup lang="ts">
import { ref } from 'vue'

import type { WorkflowExecutionDetail, WorkflowExecutionSummary, WorkflowTraceNode } from '../api/workflow-api'
import { formatDuration, statusLabel, triggerSummary } from '../model/format'
import WorkflowValueViewer from './WorkflowValueViewer.vue'

const props = defineProps<{
  /** 列表里那一行，先拿来渲染摘要，不用等详情。 */
  summary: WorkflowExecutionSummary
  /** 展开时才请求的详情；没到之前是 null。 */
  detail: WorkflowExecutionDetail | null
  loading: boolean
}>()

/** 节点输入的展开状态只属于这一次展开，收起再展开就回到默认收起。 */
const openNodes = ref<Set<number>>(new Set())

function traceNodes(): WorkflowTraceNode[] {
  return props.detail?.trace?.nodes ?? []
}

function isNodeOpen(index: number): boolean {
  return openNodes.value.has(index)
}

function toggleNode(index: number): void {
  const next = new Set(openNodes.value)
  if (next.has(index)) next.delete(index)
  else next.add(index)
  openNodes.value = next
}

function nodeDuration(node: WorkflowTraceNode): string {
  if (!node.startTime || !node.endTime) return '—'
  return formatDuration(Math.max(0, node.endTime - node.startTime))
}
</script>

<template>
  <div class="inline-detail">
    <p v-if="loading && !detail" class="inline-detail__state">正在加载执行详情…</p>
    <template v-else>
      <section class="inline-detail__section">
        <h3>执行摘要</h3>
        <dl>
          <div>
            <dt>触发来源</dt>
            <dd>{{ triggerSummary(summary) }}</dd>
          </div>
          <div v-if="summary.eventSummary">
            <dt>事件摘要</dt>
            <dd>{{ summary.eventSummary }}</dd>
          </div>
          <div>
            <dt>定义版本</dt>
            <dd>v{{ summary.definitionVersion ?? '—' }} · 耗时 {{ formatDuration(summary.durationMs) }}</dd>
          </div>
        </dl>
        <p v-if="summary.errorMessage" class="inline-detail__error">{{ summary.errorMessage }}</p>
      </section>

      <section class="inline-detail__section">
        <h3>节点执行日志</h3>
        <p v-if="!traceNodes().length" class="inline-detail__state">这条记录没有节点明细。</p>
        <ol v-else class="inline-detail__timeline">
          <li v-for="(node, index) in traceNodes()" :key="index">
            <span class="inline-detail__dot" :class="{ 'is-failed': node.status !== 'SUCCESS' }" />
            <div class="inline-detail__node">
              <header @click="toggleNode(index)">
                <span class="inline-detail__order">{{ index + 1 }}</span>
                <strong>{{ node.name || node.nodeKey || '未命名节点' }}</strong>
                <span class="inline-detail__time">{{ nodeDuration(node) }}</span>
                <span
                  class="inline-detail__status"
                  :class="{ 'is-failed': node.status !== 'SUCCESS' }"
                >
                  {{ statusLabel(node.status) }}
                </span>
              </header>
              <div class="inline-detail__node-body">
                <WorkflowValueViewer :execution-id="summary.executionId" :value="node.output" />
                <p v-if="node.error" class="inline-detail__error">{{ node.error }}</p>
                <details :open="isNodeOpen(index)">
                  <summary @click.prevent="toggleNode(index)">输入</summary>
                  <WorkflowValueViewer :execution-id="summary.executionId" :value="node.input" />
                </details>
              </div>
            </div>
          </li>
        </ol>
      </section>
    </template>
  </div>
</template>

<style scoped>
.inline-detail {
  display: grid;
  gap: var(--sys-space-4);
  border-block-start: 1px solid var(--sys-color-border);
  padding: var(--sys-space-4);
}

.inline-detail__section {
  display: grid;
  gap: var(--sys-space-2);
}

.inline-detail__section h3 {
  margin: 0;
  font: var(--sys-typography-label-strong);
}

.inline-detail__section dl {
  display: grid;
  margin: 0;
  gap: 0.35rem;
}

.inline-detail__section dl div {
  display: flex;
  gap: var(--sys-space-3);
  font: var(--sys-typography-caption);
}

.inline-detail__section dt {
  min-inline-size: 4.5rem;
  color: var(--sys-color-text-muted);
}

.inline-detail__section dd {
  min-inline-size: 0;
  margin: 0;
  overflow-wrap: anywhere;
}

.inline-detail__error {
  margin: 0;
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-caption);
}

.inline-detail__state {
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  padding: 1rem 0;
  text-align: center;
}

.inline-detail__timeline {
  display: grid;
  margin: 0;
  padding: 0;
  gap: var(--sys-space-3);
  list-style: none;
}

.inline-detail__timeline li {
  display: grid;
  grid-template-columns: 1.25rem minmax(0, 1fr);
  gap: var(--sys-space-2);
}

.inline-detail__dot {
  position: relative;
  inline-size: 0.7rem;
  block-size: 0.7rem;
  margin-block-start: 0.35rem;
  border-radius: 50%;
  background: var(--sys-color-success-text);
}

.inline-detail__dot.is-failed {
  background: var(--sys-color-danger-text);
}

.inline-detail__dot::after {
  content: '';
  position: absolute;
  inset-block-start: 1rem;
  inset-inline-start: 50%;
  inline-size: 1px;
  block-size: calc(100% + 1.6rem);
  background: var(--sys-color-border);
  transform: translateX(-50%);
}

.inline-detail__timeline li:last-child .inline-detail__dot::after {
  display: none;
}

.inline-detail__node {
  display: grid;
  gap: var(--sys-space-2);
}

.inline-detail__node header {
  display: flex;
  align-items: center;
  gap: var(--sys-space-2);
  cursor: pointer;
}

.inline-detail__order {
  display: grid;
  inline-size: 1.25rem;
  block-size: 1.25rem;
  place-items: center;
  border-radius: 50%;
  background: var(--sys-color-surface-muted);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.inline-detail__time,
.inline-detail__status {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.inline-detail__status {
  color: var(--sys-color-success-text);
  margin-inline-start: auto;
}

.inline-detail__status.is-failed {
  color: var(--sys-color-danger-text);
}

.inline-detail__node-body {
  display: grid;
  gap: var(--sys-space-2);
}

.inline-detail__node-body details summary {
  color: var(--sys-color-text-muted);
  cursor: pointer;
  font: var(--sys-typography-label);
  margin-block-end: var(--sys-space-2);
}
</style>
