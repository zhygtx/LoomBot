<script setup lang="ts">
import { computed } from 'vue'

import type { WorkflowTraceNode } from '../api/workflow-api'
import { isSuccessStatus, statusLabel } from '../model/format'
import WorkflowValueViewer from './WorkflowValueViewer.vue'

const props = withDefaults(
  defineProps<{
    /** 没传 / 传 null 表示这次执行没走到这个节点。 */
    trace?: WorkflowTraceNode | null
    /** 展示名覆盖；不传就用 trace 里的快照名。 */
    name?: string
    /** 画布上节点本身已经写了名字，卡片里就不重复。 */
    showName?: boolean
    /** 最终节点标记（只用于保存并测试）。 */
    terminal?: boolean
    /** 执行 id：大内容按它去后端取正文。 */
    executionId?: string
    /** 输入默认折叠（列表里用）；画布上输入输出都要直接可见。 */
    inputCollapsed?: boolean
    /** 画布上的紧凑预览：预览更矮，点开仍是完整数据窗口。 */
    compact?: boolean
    /** 预览框右下角的小灰字提示，例如「点击查看」。 */
    hint?: string
  }>(),
  {
    trace: null,
    name: '',
    showName: true,
    terminal: false,
    executionId: '',
    inputCollapsed: true,
    compact: false,
    hint: '',
  },
)

const failed = computed(() => Boolean(props.trace) && !isSuccessStatus(props.trace?.status))
const skipped = computed(() => !props.trace)
/** 平台调用超时这类情况：动作可能已经生效，只是没拿到结果。 */
const resultUnknown = computed(() => props.trace?.resultKnown === false)

const displayName = computed(
  () =>
    props.name?.trim() ||
    props.trace?.name?.trim() ||
    props.trace?.nodeKey ||
    props.trace?.nodeId ||
    '未命名节点',
)

const durationText = computed(() => {
  const trace = props.trace
  if (!trace?.startTime || !trace?.endTime) return ''
  const ms = Math.max(0, trace.endTime - trace.startTime)
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(2)} s`
})
</script>

<template>
  <article
    class="trace-card"
    :class="{ 'is-failed': failed, 'is-skipped': skipped, 'is-compact': compact }"
  >
    <header class="trace-card__header">
      <strong v-if="showName" class="trace-card__name">{{ displayName }}</strong>
      <span v-if="terminal" class="trace-card__badge">最终节点</span>
      <span
        v-if="resultUnknown"
        class="trace-card__badge is-unknown"
        title="动作可能已经生效，只是没收到平台响应"
      >
        结果未知
      </span>
      <span v-if="durationText" class="trace-card__duration">{{ durationText }}</span>
      <span class="trace-card__status">{{ trace ? statusLabel(trace.status) : '未执行' }}</span>
    </header>

    <template v-if="trace">
      <section class="trace-card__field">
        <span class="trace-card__label">输出</span>
        <WorkflowValueViewer
          :execution-id="executionId"
          :value="trace.output"
          :compact="compact"
          :hint="hint"
        />
      </section>

      <p v-if="trace.error" class="trace-card__error">
        {{ trace.error }}
        <span v-if="trace.errorCode" class="trace-card__error-code">{{ trace.errorCode }}</span>
      </p>

      <details v-if="inputCollapsed" class="trace-card__input">
        <summary>输入</summary>
        <WorkflowValueViewer
          :execution-id="executionId"
          :value="trace.input"
          :compact="compact"
        />
      </details>
      <section v-else class="trace-card__field">
        <span class="trace-card__label">输入</span>
        <WorkflowValueViewer
          :execution-id="executionId"
          :value="trace.input"
          :compact="compact"
          :hint="hint"
        />
      </section>
    </template>

    <p v-else class="trace-card__skipped">这次执行没有走到这个节点</p>
  </article>
</template>

<style scoped>
.trace-card {
  display: grid;
  gap: var(--sys-space-2);
  border: 1px solid var(--sys-color-border);
  border-radius: 0.65rem;
  background: color-mix(in srgb, var(--sys-color-surface-muted) 45%, transparent);
  padding: var(--sys-space-3);
}

.trace-card.is-failed {
  border-color: var(--sys-color-danger-border);
}

.trace-card.is-skipped {
  border-style: dashed;
  opacity: 0.7;
}

.trace-card.is-compact {
  gap: var(--sys-space-1, 0.25rem);
  border-radius: 0.55rem;
  background: color-mix(in srgb, var(--sys-color-surface-raised) 92%, transparent);
  padding: 0.45rem 0.5rem;
}

.trace-card__header {
  display: flex;
  align-items: center;
  gap: var(--sys-space-2);
}

.trace-card__name {
  min-inline-size: 0;
  flex: 1;
  overflow-wrap: anywhere;
}

.trace-card.is-compact .trace-card__name {
  font: var(--sys-typography-caption);
}

.trace-card__duration {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.trace-card__badge {
  flex: none;
  border-radius: 999px;
  background: var(--sys-color-surface-muted);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  padding: 0.1rem 0.5rem;
}

.trace-card__badge.is-unknown {
  background: var(--sys-color-danger-subtle);
  color: var(--sys-color-danger-text);
}

.trace-card__status {
  flex: none;
  margin-inline-start: auto;
  color: var(--sys-color-success-text);
  font: var(--sys-typography-label);
}

.trace-card.is-failed .trace-card__status,
.trace-card.is-skipped .trace-card__status {
  color: var(--sys-color-text-muted);
}

.trace-card.is-failed .trace-card__status {
  color: var(--sys-color-danger-text);
}

.trace-card__field {
  display: grid;
  gap: 0.25rem;
}

.trace-card__label {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.trace-card__error {
  margin: 0;
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-caption);
  overflow-wrap: anywhere;
}

/* 分类错误码跟在消息后面：消息是给人看的，错误码是给排查用的 */
.trace-card__error-code {
  margin-inline-start: 0.35rem;
  color: var(--sys-color-text-muted);
  font-family: var(--ref-font-mono, monospace);
  font-size: 0.72rem;
  letter-spacing: 0;
}

.trace-card__input summary {
  color: var(--sys-color-text-muted);
  cursor: pointer;
  font: var(--sys-typography-caption);
}

.trace-card__skipped {
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}
</style>
