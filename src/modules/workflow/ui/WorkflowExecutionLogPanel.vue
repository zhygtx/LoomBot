<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ChevronDown, ChevronRight, RefreshCw } from '@lucide/vue'

import { ApiError } from '@shared/api/http-client'
import { BaseButton, message } from '@shared/ui'

import {
  getExecution,
  listExecutionLog,
  listWorkflows,
  type ExecutionListQuery,
  type WorkflowExecutionDetail,
  type WorkflowExecutionSummary,
  type WorkflowSummary,
} from '../api/workflow-api'
import { formatDuration, formatTime, statusLabel, triggerSummary } from '../model/format'
import WorkflowExecutionInlineDetail from './WorkflowExecutionInlineDetail.vue'

const PAGE_SIZE = 20

/**
 * 执行日志列表：独立日志页和编辑器抽屉共用这一份。
 *
 * <p>两种点行行为：
 *
 * <ul>
 *   <li>{@code inline} —— 就地展开那一次的摘要和节点过程（独立日志页）；
 *   <li>{@code emit} —— 只抛 `select`，由父级决定（编辑器切到画布历史模式）。
 * </ul>
 */
const props = withDefaults(
  defineProps<{
    /** 注入工作流：编辑器抽屉里只查这一个工作流，并隐藏工作流筛选。 */
    workflowId?: string
    defaultIncludeTest?: boolean
    /** 嵌在抽屉里：不占页面级间距，改成抽屉内滚动。 */
    embedded?: boolean
    selectionMode?: 'inline' | 'emit'
  }>(),
  {
    workflowId: undefined,
    defaultIncludeTest: false,
    embedded: false,
    selectionMode: 'inline',
  },
)

const emit = defineEmits<{
  select: [execution: WorkflowExecutionSummary]
}>()

const workflows = ref<WorkflowSummary[]>([])
const executions = ref<WorkflowExecutionSummary[]>([])
const loading = ref(false)
const loadingMore = ref(false)

const filterWorkflowId = ref('')
const filterStatus = ref('')
const includeTest = ref(Boolean(props.defaultIncludeTest))
const keyword = ref('')

/** 内容搜索是扫 `detail_json`，一次几百毫秒起，别每敲一个字就打一次接口。 */
let keywordTimer: number | undefined

function onKeywordInput(): void {
  if (keywordTimer !== undefined) window.clearTimeout(keywordTimer)
  keywordTimer = window.setTimeout(() => void load(), 350)
}

onBeforeUnmount(() => {
  if (keywordTimer !== undefined) window.clearTimeout(keywordTimer)
})

const scopedWorkflowId = computed(() => props.workflowId || filterWorkflowId.value)
const searching = computed(() => keyword.value.trim().length > 0)
const emptyText = computed(() =>
  searching.value
    ? '没有匹配的执行记录。内容搜索最多回溯最近 5 万条，更早的可能搜不到。'
    : '还没有执行记录。工作流被事件触发或手动测试后会出现在这里。',
)
const hasMore = computed(
  () => executions.value.length > 0 && executions.value.length % PAGE_SIZE === 0,
)

/**
 * 详情（含节点 trace）只有展开某一行时才请求，列表接口不带这些内容。
 * 展开过就缓存住，重复展开不再打接口；大内容的正文仍然要再点一次才取（见 WorkflowValueViewer）。
 */
const expandedId = ref('')
const details = ref<Record<string, WorkflowExecutionDetail>>({})
const loadingDetail = ref(false)

/** 列表和「加载更多」共用一份查询条件，避免翻页时漏掉某个筛选。 */
function query(beforeId?: string): ExecutionListQuery {
  return {
    workflowId: scopedWorkflowId.value || undefined,
    status: filterStatus.value || undefined,
    includeTest: includeTest.value,
    keyword: keyword.value.trim() || undefined,
    beforeId,
    size: PAGE_SIZE,
  }
}

async function load(): Promise<void> {
  loading.value = true
  expandedId.value = ''
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

async function handleRowClick(execution: WorkflowExecutionSummary): Promise<void> {
  if (props.selectionMode === 'emit') {
    emit('select', execution)
    return
  }
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

async function loadWorkflowOptions(): Promise<void> {
  if (props.workflowId) return
  try {
    workflows.value = await listWorkflows()
  } catch {
    // 筛选下拉拉不到就退化成"全部工作流"，不影响日志本身
    workflows.value = []
  }
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
        <label class="log-panel__search">
          <span>内容</span>
          <input
            v-model="keyword"
            type="search"
            placeholder="搜节点输入输出、事件摘要、错误"
            @input="onKeywordInput"
          />
        </label>
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

    <p v-if="searching" class="log-panel__hint">
      内容搜索匹配节点的输入输出、事件摘要和错误信息，最多回溯最近 5 万条执行记录。
    </p>

    <div class="log-panel__body">
      <p v-if="loading" class="log-panel__state">正在加载执行记录…</p>
      <p v-else-if="!executions.length" class="log-panel__state">{{ emptyText }}</p>

      <ul v-else class="log-panel__list">
        <li v-for="item in executions" :key="item.id" class="log-panel__item">
          <button
            type="button"
            class="log-panel__row"
            :class="{ 'is-failed': item.status !== 'SUCCESS' }"
            @click="handleRowClick(item)"
          >
            <component
              :is="expandedId === item.executionId ? ChevronDown : ChevronRight"
              v-if="selectionMode === 'inline'"
              :size="16"
            />
            <span
              class="log-panel__status"
              :class="{ 'is-success': item.status === 'SUCCESS' }"
            >
              {{ statusLabel(item.status) }}
            </span>
            <span class="log-panel__main">
              <strong>{{ item.workflowName || `工作流 ${item.workflowId}` }}</strong>
              <small>{{ triggerSummary(item) }}</small>
              <small v-if="item.eventSummary" class="log-panel__summary">
                {{ item.eventSummary }}
              </small>
            </span>
            <span class="log-panel__meta">
              <time>{{ formatTime(item.startTime) }}</time>
              <small>{{ formatDuration(item.durationMs) }}</small>
            </span>
          </button>

          <WorkflowExecutionInlineDetail
            v-if="selectionMode === 'inline' && expandedId === item.executionId"
            :summary="item"
            :detail="details[item.executionId] ?? null"
            :loading="loadingDetail"
          />
        </li>
      </ul>

      <div v-if="hasMore" class="log-panel__more">
        <BaseButton appearance="secondary" size="small" @click="loadMore">
          {{ loadingMore ? '加载中…' : '加载更多' }}
        </BaseButton>
      </div>
    </div>
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

.log-panel__filters select,
.log-panel__search input {
  min-inline-size: 8rem;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.5rem;
  background: var(--sys-color-field);
  color: var(--sys-color-text);
  padding: 0.35rem 0.55rem;
}

.log-panel__search input {
  min-inline-size: 14rem;
}

.log-panel__search input:focus-visible {
  border-color: var(--sys-color-action-primary);
  outline: var(--sys-focus-width) solid var(--sys-color-focus);
  outline-offset: var(--sys-focus-offset);
}

.log-panel__hint {
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
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

.log-panel__item {
  overflow: hidden;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.7rem;
  background: var(--sys-color-surface-raised);
}

.log-panel__row {
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

.log-panel__row.is-failed {
  border-inline-start: 0.22rem solid var(--sys-color-danger-text);
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
