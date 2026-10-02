<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RefreshCw } from '@lucide/vue'

import { ApiError } from '@shared/api/http-client'
import { BaseButton, message } from '@shared/ui'

import {
  listExecutionLog,
  listWorkflows,
  type WorkflowExecutionSummary,
  type WorkflowSummary,
} from '../api/workflow-api'
import { formatDuration, formatTime, statusLabel, triggerSummary } from '../model/format'
import WorkflowExecutionDetailDrawer from './WorkflowExecutionDetailDrawer.vue'

const PAGE_SIZE = 20

const props = withDefaults(
  defineProps<{
    /** 注入工作流：编辑器抽屉里只查这一个工作流，并隐藏工作流筛选。 */
    workflowId?: string
    defaultIncludeTest?: boolean
    /** 嵌在抽屉里：不占页面级间距，改成抽屉内滚动。 */
    embedded?: boolean
    /**
     * drawer：点行在面板内再开一层执行详情抽屉（独立日志页/列表默认）。
     * emit：点行只抛出 select，由父级决定（编辑器切到画布历史模式）。
     */
    selectionMode?: 'drawer' | 'emit'
  }>(),
  {
    workflowId: undefined,
    defaultIncludeTest: false,
    embedded: false,
    selectionMode: 'drawer',
  },
)

const emit = defineEmits<{
  select: [execution: WorkflowExecutionSummary]
}>()

const workflows = ref<WorkflowSummary[]>([])
const executions = ref<WorkflowExecutionSummary[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const selected = ref<WorkflowExecutionSummary | null>(null)

const filterWorkflowId = ref('')
const filterStatus = ref('')
const includeTest = ref(Boolean(props.defaultIncludeTest))

const scopedWorkflowId = computed(() => props.workflowId || filterWorkflowId.value)
const hasMore = computed(
  () => executions.value.length > 0 && executions.value.length % PAGE_SIZE === 0,
)

function query(beforeId?: string) {
  return {
    workflowId: scopedWorkflowId.value || undefined,
    status: filterStatus.value || undefined,
    includeTest: includeTest.value,
    beforeId,
    size: PAGE_SIZE,
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    executions.value = await listExecutionLog(query())
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
    const next = await listExecutionLog(query(last.id))
    executions.value = [...executions.value, ...next]
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '加载更多失败')
  } finally {
    loadingMore.value = false
  }
}

async function loadWorkflowOptions(): Promise<void> {
  if (props.workflowId) return
  try {
    workflows.value = await listWorkflows()
  } catch {
    // 筛选下拉拉不到就退化成"全部工作流"，不影响日志本身
    workflows.value = []
  }
}

function handleRowClick(item: WorkflowExecutionSummary): void {
  if (props.selectionMode === 'emit') {
    emit('select', item)
    return
  }
  selected.value = item
}

onMounted(async () => {
  await loadWorkflowOptions()
  await load()
})
</script>

<template>
  <div class="log-panel" :class="{ 'is-embedded': embedded }">
    <div class="log-panel__bar">
      <div class="log-panel__filters">
        <label v-if="!workflowId">
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
        <label class="log-panel__check">
          <input v-model="includeTest" type="checkbox" @change="load" />
          <span>包含测试执行</span>
        </label>
      </div>
      <BaseButton appearance="secondary" size="small" @click="load">
        <RefreshCw :size="15" />
        刷新
      </BaseButton>
    </div>

    <div class="log-panel__body">
      <p v-if="loading" class="log-panel__state">正在加载执行记录…</p>
      <p v-else-if="!executions.length" class="log-panel__state">
        还没有执行记录。工作流被事件触发或手动测试后会出现在这里。
      </p>

      <ul v-else class="log-panel__list">
        <li v-for="item in executions" :key="item.id">
          <button type="button" class="log-panel__row" @click="handleRowClick(item)">
            <span
              class="log-panel__status"
              :class="{ 'is-success': item.status === 'SUCCESS' }"
            >
              {{ statusLabel(item.status) }}
            </span>
            <span class="log-panel__main">
              <strong>{{ item.workflowName || `工作流 ${item.workflowId}` }}</strong>
              <small>
                {{ triggerSummary(item) }}
              </small>
              <small v-if="item.eventSummary" class="log-panel__summary">
                {{ item.eventSummary }}
              </small>
            </span>
            <span class="log-panel__meta">
              <time>{{ formatTime(item.startTime) }}</time>
              <small>{{ formatDuration(item.durationMs) }}</small>
            </span>
          </button>
        </li>
      </ul>

      <div v-if="hasMore" class="log-panel__more">
        <BaseButton appearance="secondary" size="small" @click="loadMore">
          {{ loadingMore ? '加载中…' : '加载更多' }}
        </BaseButton>
      </div>
    </div>

    <WorkflowExecutionDetailDrawer
      v-if="selected && selectionMode === 'drawer'"
      :execution-id="selected.executionId"
      :summary="selected"
      @close="selected = null"
    />
  </div>
</template>

<style scoped>
.log-panel {
  display: grid;
  align-content: start;
  gap: var(--sys-space-4);
}

.log-panel.is-embedded {
  min-block-size: 0;
  gap: var(--sys-space-3);
  padding: var(--sys-space-3);
}

.log-panel__bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sys-space-3);
}

.log-panel__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--sys-space-3);
}

.log-panel__filters label {
  display: grid;
  gap: 0.3rem;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.log-panel__filters select {
  min-inline-size: 8rem;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.5rem;
  background: var(--sys-color-field);
  color: var(--sys-color-text);
  padding: 0.35rem 0.55rem;
}

.log-panel__check {
  display: flex !important;
  align-items: center;
  gap: 0.4rem;
  padding-block-end: 0.4rem;
}

.log-panel__body {
  display: grid;
  min-block-size: 0;
  align-content: start;
  gap: var(--sys-space-2);
}

.log-panel__state {
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  padding: 2rem 0;
  text-align: center;
}

.log-panel__list {
  display: grid;
  margin: 0;
  padding: 0;
  gap: var(--sys-space-2);
  list-style: none;
}

.log-panel__row {
  display: flex;
  inline-size: 100%;
  align-items: center;
  gap: var(--sys-space-3);
  border: 1px solid var(--sys-color-border);
  border-radius: 0.65rem;
  background: var(--sys-color-surface-raised);
  color: inherit;
  cursor: pointer;
  padding: var(--sys-space-3);
  text-align: start;
}

.log-panel__row:hover {
  border-color: color-mix(in srgb, var(--sys-color-border) 60%, var(--sys-color-text));
}

.log-panel__status {
  flex: none;
  border-radius: 999px;
  background: var(--sys-color-danger-subtle);
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-label);
  padding: 0.15rem 0.55rem;
}

.log-panel__status.is-success {
  background: var(--sys-color-success-subtle);
  color: var(--sys-color-success-text);
}

.log-panel__main {
  display: grid;
  min-inline-size: 0;
  flex: 1;
  gap: 0.15rem;
}

.log-panel__main small,
.log-panel__meta small,
.log-panel__meta time {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.log-panel__main small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.log-panel__summary {
  opacity: 0.85;
}

.log-panel__meta {
  display: grid;
  flex: none;
  gap: 0.2rem;
  text-align: end;
}

.log-panel__more {
  display: flex;
  justify-content: center;
  padding-block: var(--sys-space-2);
}

@media (max-width: 40rem) {
  .log-panel__bar {
    flex-direction: column;
    align-items: stretch;
  }

  .log-panel__row {
    flex-wrap: wrap;
  }

  .log-panel__meta {
    inline-size: 100%;
    text-align: start;
  }
}
</style>
