<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ChevronDown, ChevronRight, RefreshCw } from '@lucide/vue'

import { ApiError } from '@shared/api/http-client'
import { BaseButton, message } from '@shared/ui'

import {
  getExecution,
  listExecutionLog,
  listWorkflows,
  type WorkflowExecutionDetail,
  type WorkflowExecutionSummary,
  type WorkflowSummary,
  type WorkflowTraceNode,
} from '../api/workflow-api'
import { formatDuration, formatTime, statusLabel, triggerSummary } from '../model/format'
import WorkflowValueViewer from '../ui/WorkflowValueViewer.vue'

const PAGE_SIZE = 20

const workflows = ref<WorkflowSummary[]>([])
const executions = ref<WorkflowExecutionSummary[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const filterWorkflowId = ref('')
const filterStatus = ref('')
const includeTest = ref(false)

/**
 * 详情（含节点 trace）只有展开某一行时才请求，列表接口不带这些内容。
 * 展开过就缓存住，重复展开不再打接口；大内容的正文仍然要再点一次才取（见 WorkflowValueViewer）。
 */
const expandedId = ref('')
const details = ref<Record<string, WorkflowExecutionDetail>>({})
const loadingDetail = ref(false)
const expandedNodes = ref<Set<string>>(new Set())

async function load(): Promise<void> {
  loading.value = true
  expandedId.value = ''
  try {
    executions.value = await listExecutionLog({
      workflowId: filterWorkflowId.value || undefined,
      status: filterStatus.value || undefined,
      includeTest: includeTest.value,
      size: PAGE_SIZE,
    })
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '执行日志加载失败')
  } finally {
    loading.value = false
  }
}

async function loadMore(): Promise<void> {
  const last = executions.value[executions.value.length - 1]
  if (!last) return
  loadingMore.value = true
  try {
    const next = await listExecutionLog({
      workflowId: filterWorkflowId.value || undefined,
      status: filterStatus.value || undefined,
      includeTest: includeTest.value,
      beforeId: last.id,
      size: PAGE_SIZE,
    })
    executions.value = [...executions.value, ...next]
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '加载更多失败')
  } finally {
    loadingMore.value = false
  }
}

async function toggle(execution: WorkflowExecutionSummary): Promise<void> {
  if (expandedId.value === execution.executionId) {
    expandedId.value = ''
    return
  }
  expandedId.value = execution.executionId
  if (details.value[execution.executionId]) return
  loadingDetail.value = true
  try {
    details.value = {
      ...details.value,
      [execution.executionId]: await getExecution(execution.executionId),
    }
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '执行详情加载失败')
    expandedId.value = ''
  } finally {
    loadingDetail.value = false
  }
}

function traceOf(executionId: string): WorkflowTraceNode[] {
  return details.value[executionId]?.trace?.nodes ?? []
}

function nodeKeyOf(executionId: string, index: number): string {
  return `${executionId}:${index}`
}

function nodeOpen(executionId: string, index: number): boolean {
  return expandedNodes.value.has(nodeKeyOf(executionId, index))
}

function toggleNode(executionId: string, index: number): void {
  const next = new Set(expandedNodes.value)
  const key = nodeKeyOf(executionId, index)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedNodes.value = next
}

function nodeDuration(node: WorkflowTraceNode): string {
  if (!node.startTime || !node.endTime) return '—'
  return formatDuration(Math.max(0, node.endTime - node.startTime))
}

onMounted(async () => {
  try {
    workflows.value = await listWorkflows()
  } catch {
    workflows.value = []
  }
  await load()
})
</script>

<template>
  <section class="workflow-log">
    <header class="workflow-log__head">
      <div>
        <h1>执行日志</h1>
        <p>每次执行的触发来源、节点过程和结果，保留最近 7 天。</p>
      </div>
      <BaseButton appearance="secondary" size="small" @click="load">
        <RefreshCw :size="15" />
        刷新
      </BaseButton>
    </header>

    <div class="workflow-log__filters">
      <label>
        <span>工作流</span>
        <select v-model="filterWorkflowId" @change="load">
          <option value="">全部</option>
          <option v-for="item in workflows" :key="item.id" :value="item.id">
            {{ item.name }}
          </option>
        </select>
      </label>
      <label>
        <span>状态</span>
        <select v-model="filterStatus" @change="load">
          <option value="">全部</option>
          <option value="SUCCESS">成功</option>
          <option value="TIMEOUT">超时</option>
          <option value="FAILED">失败</option>
        </select>
      </label>
      <label class="workflow-log__check">
        <input v-model="includeTest" type="checkbox" @change="load" />
        <span>包含测试执行</span>
      </label>
    </div>

    <p v-if="loading" class="workflow-log__state">正在加载执行记录…</p>
    <p v-else-if="!executions.length" class="workflow-log__state">
      还没有执行记录。工作流被事件触发或手动测试后会出现在这里。
    </p>

    <ul v-else class="workflow-log__list">
      <li v-for="item in executions" :key="item.id" class="workflow-log__item">
        <button
          type="button"
          class="workflow-log__row"
          :class="{ 'is-failed': item.status !== 'SUCCESS' }"
          @click="toggle(item)"
        >
          <component :is="expandedId === item.executionId ? ChevronDown : ChevronRight" :size="16" />
          <span class="workflow-log__status" :class="{ 'is-success': item.status === 'SUCCESS' }">
            {{ statusLabel(item.status) }}
          </span>
          <span class="workflow-log__main">
            <strong>{{ item.workflowName || `工作流 ${item.workflowId}` }}</strong>
            <small>{{ triggerSummary(item) }}</small>
          </span>
          <span class="workflow-log__meta">
            <time>{{ formatTime(item.startTime) }}</time>
            <small>{{ formatDuration(item.durationMs) }}</small>
          </span>
        </button>

        <div v-if="expandedId === item.executionId" class="workflow-log__detail">
          <p v-if="loadingDetail && !details[item.executionId]" class="workflow-log__state">
            正在加载执行详情…
          </p>
          <template v-else>
            <section class="workflow-log__summary">
              <h3>执行摘要</h3>
              <dl>
                <div>
                  <dt>触发来源</dt>
                  <dd>{{ triggerSummary(item) }}</dd>
                </div>
                <div v-if="item.eventSummary">
                  <dt>事件摘要</dt>
                  <dd>{{ item.eventSummary }}</dd>
                </div>
                <div>
                  <dt>定义版本</dt>
                  <dd>v{{ item.definitionVersion ?? '—' }} · 耗时 {{ formatDuration(item.durationMs) }}</dd>
                </div>
              </dl>
              <p v-if="item.errorMessage" class="workflow-log__error">{{ item.errorMessage }}</p>
            </section>

            <section class="workflow-log__nodes">
              <h3>节点执行日志</h3>
              <p v-if="!traceOf(item.executionId).length" class="workflow-log__state">
                这条记录没有节点明细。
              </p>
              <ol v-else class="workflow-log__timeline">
                <li v-for="(node, index) in traceOf(item.executionId)" :key="index">
                  <span class="workflow-log__dot" :class="{ 'is-failed': node.status !== 'SUCCESS' }" />
                  <div class="workflow-log__node">
                    <header @click="toggleNode(item.executionId, index)">
                      <span class="workflow-log__order">{{ index + 1 }}</span>
                      <strong>{{ node.name || node.nodeKey || '未命名节点' }}</strong>
                      <span class="workflow-log__node-time">{{ nodeDuration(node) }}</span>
                      <span
                        class="workflow-log__node-status"
                        :class="{ 'is-failed': node.status !== 'SUCCESS' }"
                      >
                        {{ statusLabel(node.status) }}
                      </span>
                    </header>
                    <div class="workflow-log__node-body">
                      <WorkflowValueViewer :execution-id="item.executionId" :value="node.output" />
                      <p v-if="node.error" class="workflow-log__error">{{ node.error }}</p>
                      <details :open="nodeOpen(item.executionId, index)">
                        <summary @click.prevent="toggleNode(item.executionId, index)">输入</summary>
                        <WorkflowValueViewer :execution-id="item.executionId" :value="node.input" />
                      </details>
                    </div>
                  </div>
                </li>
              </ol>
            </section>
          </template>
        </div>
      </li>
    </ul>

    <div v-if="executions.length && executions.length % PAGE_SIZE === 0" class="workflow-log__more">
      <BaseButton appearance="secondary" size="small" @click="loadMore">
        {{ loadingMore ? '加载中…' : '加载更多' }}
      </BaseButton>
    </div>
  </section>
</template>

<style scoped>
.workflow-log {
  display: grid;
  align-content: start;
  gap: var(--sys-space-4);
  padding: var(--sys-space-5);
}

.workflow-log__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sys-space-4);
}

.workflow-log__head h1 {
  margin: 0 0 0.35rem;
  font-size: 20px;
}

.workflow-log__head p {
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-log__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--sys-space-3);
}

.workflow-log__filters label {
  display: grid;
  gap: 0.3rem;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-log__filters select {
  min-inline-size: 10rem;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.5rem;
  background: var(--sys-color-field);
  color: var(--sys-color-text);
  padding: 0.4rem 0.6rem;
}

.workflow-log__check {
  display: flex !important;
  align-items: center;
  gap: 0.4rem;
  padding-block-end: 0.5rem;
}

.workflow-log__state {
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  padding: 1.5rem 0;
  text-align: center;
}

.workflow-log__list {
  display: grid;
  margin: 0;
  padding: 0;
  gap: var(--sys-space-2);
  list-style: none;
}

.workflow-log__item {
  border: 1px solid var(--sys-color-border);
  border-radius: 0.7rem;
  background: var(--sys-color-surface-raised);
  overflow: hidden;
}

.workflow-log__row {
  display: flex;
  inline-size: 100%;
  align-items: center;
  gap: var(--sys-space-3);
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  padding: var(--sys-space-3);
  text-align: start;
}

.workflow-log__row.is-failed {
  border-inline-start: 0.22rem solid var(--sys-color-danger-text);
}

.workflow-log__status {
  flex: none;
  border-radius: 999px;
  background: var(--sys-color-danger-subtle);
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-label);
  padding: 0.15rem 0.55rem;
}

.workflow-log__status.is-success {
  background: var(--sys-color-success-subtle);
  color: var(--sys-color-success-text);
}

.workflow-log__main {
  display: grid;
  min-inline-size: 0;
  flex: 1;
  gap: 0.2rem;
}

.workflow-log__main small,
.workflow-log__meta small,
.workflow-log__meta time {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-log__meta {
  display: grid;
  flex: none;
  gap: 0.2rem;
  text-align: end;
}

.workflow-log__detail {
  display: grid;
  gap: var(--sys-space-4);
  border-block-start: 1px solid var(--sys-color-border);
  padding: var(--sys-space-4);
}

.workflow-log__summary,
.workflow-log__nodes {
  display: grid;
  gap: var(--sys-space-2);
}

.workflow-log__summary h3,
.workflow-log__nodes h3 {
  margin: 0;
  font: var(--sys-typography-label-strong);
}

.workflow-log__summary dl {
  display: grid;
  margin: 0;
  gap: 0.35rem;
}

.workflow-log__summary dl div {
  display: flex;
  gap: var(--sys-space-3);
  font: var(--sys-typography-caption);
}

.workflow-log__summary dt {
  min-inline-size: 4.5rem;
  color: var(--sys-color-text-muted);
}

.workflow-log__summary dd {
  min-inline-size: 0;
  margin: 0;
  overflow-wrap: anywhere;
}

.workflow-log__error {
  margin: 0;
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-caption);
}

.workflow-log__timeline {
  display: grid;
  margin: 0;
  padding: 0;
  gap: var(--sys-space-3);
  list-style: none;
}

.workflow-log__timeline li {
  display: grid;
  grid-template-columns: 1.25rem minmax(0, 1fr);
  gap: var(--sys-space-2);
}

.workflow-log__dot {
  position: relative;
  inline-size: 0.7rem;
  block-size: 0.7rem;
  margin-block-start: 0.35rem;
  border-radius: 50%;
  background: var(--sys-color-success-text);
}

.workflow-log__dot.is-failed {
  background: var(--sys-color-danger-text);
}

.workflow-log__dot::after {
  content: '';
  position: absolute;
  inset-block-start: 1rem;
  inset-inline-start: 50%;
  inline-size: 1px;
  block-size: calc(100% + 1.6rem);
  background: var(--sys-color-border);
  transform: translateX(-50%);
}

.workflow-log__timeline li:last-child .workflow-log__dot::after {
  display: none;
}

.workflow-log__node {
  display: grid;
  gap: var(--sys-space-2);
}

.workflow-log__node header {
  display: flex;
  align-items: center;
  gap: var(--sys-space-2);
  cursor: pointer;
}

.workflow-log__order {
  display: grid;
  inline-size: 1.25rem;
  block-size: 1.25rem;
  place-items: center;
  border-radius: 50%;
  background: var(--sys-color-surface-muted);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-log__node-time,
.workflow-log__node-status {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-log__node-status {
  color: var(--sys-color-success-text);
  margin-inline-start: auto;
}

.workflow-log__node-status.is-failed {
  color: var(--sys-color-danger-text);
}

.workflow-log__node-body {
  display: grid;
  gap: var(--sys-space-2);
}

.workflow-log__node-body details summary {
  color: var(--sys-color-text-muted);
  cursor: pointer;
  font: var(--sys-typography-label);
  margin-block-end: var(--sys-space-2);
}

.workflow-log__more {
  display: flex;
  justify-content: center;
  padding-block: var(--sys-space-3);
}

@media (max-width: 40rem) {
  .workflow-log__row {
    flex-wrap: wrap;
  }

  .workflow-log__meta {
    inline-size: 100%;
    text-align: start;
  }
}
</style>
