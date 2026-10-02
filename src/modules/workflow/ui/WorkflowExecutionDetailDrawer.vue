<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { X } from '@lucide/vue'

import { ApiError } from '@shared/api/http-client'

import {
  getExecution,
  type WorkflowExecutionDetail,
  type WorkflowExecutionSummary,
} from '../api/workflow-api'
import {
  formatDuration,
  formatTime,
  statusLabel,
  triggerLabel,
} from '../model/format'
import WorkflowNodeTraceList from './WorkflowNodeTraceList.vue'

const props = defineProps<{
  executionId: string
  /** 列表里已有的行，先进来先显示，避免白屏。 */
  summary?: WorkflowExecutionSummary | null
}>()

defineEmits<{
  close: []
}>()

const detail = ref<WorkflowExecutionDetail | null>(null)
const loading = ref(false)
const errorText = ref('')

const header = computed<WorkflowExecutionSummary | null>(
  () => detail.value?.summary ?? props.summary ?? null,
)
const traceNodes = computed(() => detail.value?.trace?.nodes ?? [])
const traceTruncated = computed(
  () => Boolean(detail.value?.trace?.truncated) || header.value?.detailTruncated === 1,
)
const traceUnavailable = computed(() => !loading.value && !errorText.value && !traceNodes.value.length)

async function load(): Promise<void> {
  if (!props.executionId) return
  loading.value = true
  errorText.value = ''
  try {
    detail.value = await getExecution(props.executionId)
  } catch (error) {
    errorText.value = error instanceof ApiError ? error.message : '执行详情加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.executionId, load, { immediate: true })
</script>

<template>
  <div class="workflow-execution" @wheel.stop @click.self="$emit('close')">
    <aside class="workflow-execution__panel" role="dialog" aria-label="执行详情">
    <header class="workflow-execution__header">
      <div>
        <strong>{{ header?.workflowName || '执行详情' }}</strong>
        <small>{{ header?.executionId || executionId }}</small>
      </div>
      <button type="button" aria-label="关闭执行详情" @click="$emit('close')">
        <X :size="18" />
      </button>
    </header>

    <div class="workflow-execution__body">
      <p v-if="loading" class="workflow-execution__state">正在加载执行详情…</p>
      <p v-else-if="errorText" class="workflow-execution__state is-error">{{ errorText }}</p>

      <template v-else>
        <section v-if="header" class="workflow-execution__meta">
          <div class="workflow-execution__badges">
            <span
              class="workflow-execution__status"
              :class="{ 'is-success': header.status === 'SUCCESS' }"
            >
              {{ statusLabel(header.status) }}
            </span>
            <span class="workflow-execution__badge">{{ triggerLabel(header.triggerType) }}</span>
          </div>
          <dl>
            <div>
              <dt>开始时间</dt>
              <dd>{{ formatTime(header.startTime) }}</dd>
            </div>
            <div>
              <dt>耗时</dt>
              <dd>{{ formatDuration(header.durationMs) }}</dd>
            </div>
            <div>
              <dt>定义版本</dt>
              <dd>v{{ header.definitionVersion ?? '—' }}</dd>
            </div>
            <div v-if="header.connectionId">
              <dt>连接</dt>
              <dd>{{ header.connectionName || '连接已删除' }}</dd>
            </div>
            <div v-if="header.eventNodeName || header.eventNodeKey">
              <dt>事件节点</dt>
              <dd>{{ header.eventNodeName || header.eventNodeKey }}</dd>
            </div>
            <div v-if="header.eventSummary">
              <dt>事件摘要</dt>
              <dd>{{ header.eventSummary }}</dd>
            </div>
          </dl>
          <p v-if="header.errorMessage" class="workflow-execution__error">
            {{ header.errorMessage }}
          </p>
        </section>

        <p v-if="traceTruncated" class="workflow-execution__notice">
          明细超过单条上限，已截断为节点摘要。
        </p>

        <section class="workflow-execution__section">
          <h3>节点过程</h3>
          <WorkflowNodeTraceList :nodes="traceNodes" :execution-id="executionId" />
          <p v-if="traceUnavailable" class="workflow-execution__state">
            这条记录没有可用的节点明细。
          </p>
        </section>
      </template>
    </div>
    </aside>
  </div>
</template>

<style scoped>
.workflow-execution {
  position: fixed;
  z-index: 46;
  inset: 0;
  display: flex;
  justify-content: flex-end;
  padding-block: 6.25rem 1rem;
  padding-inline-end: 1rem;
}

.workflow-execution__panel {
  display: flex;
  inline-size: min(30rem, calc(100vw - 2rem));
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.75rem;
  background: color-mix(in srgb, var(--sys-color-surface-raised) 97%, transparent);
  box-shadow: var(--sys-shadow-raised);
}

.workflow-execution__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  border-block-end: 1px solid var(--sys-color-border);
  gap: var(--sys-space-3);
  padding: var(--sys-space-4);
}

.workflow-execution__header strong,
.workflow-execution__header small {
  display: block;
}

.workflow-execution__header strong {
  font: var(--sys-typography-label-strong);
}

.workflow-execution__header small {
  margin-block-start: 0.2rem;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  overflow-wrap: anywhere;
}

.workflow-execution__header button {
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

.workflow-execution__body {
  display: grid;
  min-block-size: 0;
  flex: 1;
  align-content: start;
  gap: var(--sys-space-4);
  overflow-y: auto;
  padding: var(--sys-space-4);
}

.workflow-execution__state {
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  padding: 1.5rem 0;
  text-align: center;
}

.workflow-execution__state.is-error {
  color: var(--sys-color-danger-text);
}

.workflow-execution__meta {
  display: grid;
  gap: var(--sys-space-3);
  border: 1px solid var(--sys-color-border);
  border-radius: 0.65rem;
  padding: var(--sys-space-3);
}

.workflow-execution__badges {
  display: flex;
  gap: var(--sys-space-2);
}

.workflow-execution__status,
.workflow-execution__badge {
  border-radius: 999px;
  font: var(--sys-typography-label);
  padding: 0.15rem 0.55rem;
}

.workflow-execution__status {
  background: var(--sys-color-danger-subtle);
  color: var(--sys-color-danger-text);
}

.workflow-execution__status.is-success {
  background: var(--sys-color-success-subtle);
  color: var(--sys-color-success-text);
}

.workflow-execution__badge {
  background: var(--sys-color-surface-muted);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-execution__meta dl {
  display: grid;
  margin: 0;
  gap: 0.35rem;
}

.workflow-execution__meta dl div {
  display: flex;
  gap: var(--sys-space-3);
  font: var(--sys-typography-caption);
}

.workflow-execution__meta dt {
  min-inline-size: 4.5rem;
  color: var(--sys-color-text-muted);
}

.workflow-execution__meta dd {
  min-inline-size: 0;
  margin: 0;
  overflow-wrap: anywhere;
}

.workflow-execution__error {
  margin: 0;
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-caption);
}

.workflow-execution__notice {
  margin: 0;
  border: 1px solid var(--sys-color-danger-border);
  border-radius: 0.5rem;
  background: var(--sys-color-danger-subtle);
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-caption);
  padding: var(--sys-space-2) var(--sys-space-3);
}

.workflow-execution__section {
  display: grid;
  gap: var(--sys-space-2);
}

.workflow-execution__section h3 {
  margin: 0;
  font: var(--sys-typography-label-strong);
}

@media (max-width: 56rem) {
  .workflow-execution {
    align-items: flex-end;
    padding: 0 0.75rem 0.75rem;
  }

  .workflow-execution__panel {
    inline-size: 100%;
    max-block-size: min(32rem, calc(100dvh - 6rem));
  }
}
</style>
