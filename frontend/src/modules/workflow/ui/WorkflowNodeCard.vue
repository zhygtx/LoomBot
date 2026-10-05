<script setup lang="ts">
import { computed, ref } from 'vue'
import { Cable, TriangleAlert } from '@lucide/vue'

import type { WorkflowNode, WorkflowNodeAlert } from '../api/workflow-api'
import {
  isAdapterNode,
  nodeAccent,
  nodeKindOf,
  nodeLabel,
  nodeTypeLabel,
  sortParameters,
} from '../model/graph'

const props = withDefaults(
  defineProps<{
    node: WorkflowNode
    selected?: boolean
    connecting?: boolean
    /** 历史日志模式：只可点选查看，不能拖动、连线、右键或打开配置。 */
    readonly?: boolean
    /** 历史模式下的执行状态：成功绿框、失败红框、未执行灰虚线框。 */
    status?: 'success' | 'failed' | 'skipped' | null
    /** 这个节点引用的插件已变更或已删除，由后端标出来。 */
    alert?: WorkflowNodeAlert | null
  }>(),
  {
    selected: false,
    connecting: false,
    readonly: false,
    status: null,
    alert: null,
  },
)

defineEmits<{
  select: [node: WorkflowNode]
  'move-start': [event: PointerEvent, node: WorkflowNode]
  'connect-start': [event: PointerEvent, node: WorkflowNode, port: 'success' | 'failure']
  contextmenu: [event: MouseEvent, node: WorkflowNode]
  'open-config': [node: WorkflowNode]
  /** 把节点更新到当前插件版本。 */
  upgrade: [node: WorkflowNode]
}>()

const root = ref<HTMLElement | null>(null)
const parameters = computed(() => sortParameters(props.node.descriptor?.parameters))
const adapter = computed(() => isAdapterNode(props.node))
const connectionReady = computed(() => Boolean(props.node.connectionId))

/** 主色由节点种类决定（事件棕 / 动作绿 / 节点蓝），本组件只负责把它挂成 CSS 变量。 */
const accentStyle = computed(() => ({
  left: `${props.node.x}px`,
  top: `${props.node.y}px`,
  '--node-accent': nodeAccent(nodeKindOf(props.node)),
}))

defineExpose({ root })
</script>

<template>
  <article
    ref="root"
    class="workflow-node"
    :class="{
      'is-selected': selected,
      'is-branch': Boolean(node.branch),
      'is-readonly': readonly,
      'is-status-success': status === 'success',
      'is-status-failed': status === 'failed',
      'is-status-skipped': status === 'skipped',
      'is-alerted': Boolean(alert),
    }"
    :data-node-id="node.id"
    :style="accentStyle"
    @pointerdown.stop="!readonly && $emit('move-start', $event, node)"
    @click.stop="$emit('select', node)"
    @dblclick.stop="!readonly && $emit('open-config', node)"
    @contextmenu.prevent.stop="!readonly && $emit('contextmenu', $event, node)"
  >
    <div class="workflow-node__body">
      <span
        v-if="!readonly"
        class="workflow-node__port workflow-node__port--input"
        aria-hidden="true"
      />

      <div class="workflow-node__content">
        <header class="workflow-node__header">
          <span class="workflow-node__title">{{ nodeLabel(node) }}</span>
          <span class="workflow-node__kind">{{ nodeTypeLabel(node) }}</span>
        </header>

        <p v-if="node.descriptor?.description" class="workflow-node__description">
          {{ node.descriptor.description }}
        </p>

        <div v-if="parameters.length" class="workflow-node__parameters">
          <span
            v-for="parameter in parameters.slice(0, 4)"
            :key="parameter.name"
            class="workflow-node__parameter"
          >
            {{ parameter.type || 'object' }} {{ parameter.displayName || parameter.name }}
          </span>
          <span v-if="parameters.length > 4" class="workflow-node__parameter">
            +{{ parameters.length - 4 }}
          </span>
        </div>

        <footer class="workflow-node__footer">
          <span
            v-if="adapter"
            class="workflow-node__adapter"
            :class="{ 'is-ready': connectionReady }"
          >
            <Cable :size="12" />
            {{ connectionReady ? '已绑定连接' : '待绑定连接' }}
          </span>
          <span class="workflow-node__return">
            返回值
            <code>{{ node.descriptor?.returnType || 'void' }}</code>
          </span>
        </footer>

        <p v-if="alert" class="workflow-node__alert">
          <TriangleAlert :size="12" />
          <span>{{ alert.reason === 'REMOVED' ? '插件节点已删除' : '插件节点已变更' }}</span>
          <button
            v-if="!readonly && alert.reason !== 'REMOVED'"
            type="button"
            @click.stop="$emit('upgrade', node)"
          >
            更新
          </button>
        </p>
      </div>

      <span
        v-if="!readonly"
        class="workflow-node__port workflow-node__port--success"
        :class="{ 'is-drawing': connecting }"
        title="成功输出"
        @pointerdown.stop.prevent="$emit('connect-start', $event, node, 'success')"
      />
      <span
        v-if="node.branch && !readonly"
        class="workflow-node__port workflow-node__port--failure"
        title="失败输出"
        @pointerdown.stop.prevent="$emit('connect-start', $event, node, 'failure')"
      />
    </div>
  </article>
</template>

<style scoped>
.workflow-node {
  position: absolute;
  z-index: 20;
  inline-size: 15.625rem;
  cursor: grab;
  user-select: none;
  /* 节点自己声明不参与滚动/缩放手势：有些移动端浏览器不认祖先的 touch-action，
     不然按住节点拖动会被当成页面滚动，节点纹丝不动。 */
  touch-action: none;
}

.workflow-node:active {
  cursor: grabbing;
}

.workflow-node.is-readonly,
.workflow-node.is-readonly:active {
  cursor: pointer;
}

.workflow-node__body {
  position: relative;
  min-block-size: 6.5rem;
  border: 1px solid var(--sys-color-border-strong);
  border-inline-start: 0.22rem solid var(--node-accent, var(--sys-color-action-primary));
  border-radius: 0.7rem;
  background: color-mix(in srgb, var(--sys-color-surface-raised) 96%, transparent);
  box-shadow: 0 0.45rem 1.1rem color-mix(in srgb, var(--sys-color-text) 8%, transparent);
  padding: 0.75rem;
  /* 只过渡颜色和阴影，不过渡 transform：画布整体缩放时，带 transform 的子层级会留下
     旧分辨率的栅格，节点文字会一直糊到下一次重绘（缩放后新放节点时最容易复现）。 */
  transition:
    border-color var(--sys-motion-fast),
    box-shadow var(--sys-motion-fast);
}

.workflow-node:hover .workflow-node__body {
  border-color: color-mix(
    in srgb,
    var(--node-accent, var(--sys-color-action-primary)) 42%,
    var(--sys-color-border-strong)
  );
  box-shadow:
    0 0.9rem 1.6rem color-mix(in srgb, var(--sys-color-text) 12%, transparent),
    0 0 0 0.12rem color-mix(in srgb, var(--node-accent, var(--sys-color-action-primary)) 28%, transparent);
}

.workflow-node.is-selected .workflow-node__body {
  border-color: var(--node-accent, var(--sys-color-action-primary));
  box-shadow: 0 0 0 0.18rem var(--sys-color-focus);
}

/*
 * 历史日志模式：整张卡片的边框跟执行状态走。放在 is-selected 之后，
 * 这样选中时也仍然看得到状态色；选中反馈改用 outline，两者不互相覆盖。
 */
.workflow-node.is-status-success .workflow-node__body {
  border-color: var(--sys-color-success-text);
  box-shadow: 0 0 0 0.1rem color-mix(in srgb, var(--sys-color-success-text) 28%, transparent);
}

.workflow-node.is-status-failed .workflow-node__body {
  border-color: var(--sys-color-danger);
  box-shadow: 0 0 0 0.1rem color-mix(in srgb, var(--sys-color-danger) 28%, transparent);
}

.workflow-node.is-status-skipped .workflow-node__body {
  border-color: var(--sys-color-border-strong);
  border-style: dashed;
  opacity: 0.66;
}

.workflow-node.is-readonly.is-selected .workflow-node__body {
  outline: 0.16rem solid var(--sys-color-focus);
  outline-offset: 0.12rem;
}

/* 引用的插件变了 / 没了：用警示色圈出来，并在卡片下方给一个更新入口 */
.workflow-node.is-alerted .workflow-node__body {
  border-color: var(--sys-color-warning-text);
  box-shadow: 0 0 0 0.1rem color-mix(in srgb, var(--sys-color-warning-text) 30%, transparent);
}

.workflow-node__alert {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  margin: 0.4rem 0 0;
  border-radius: 0.35rem;
  background: color-mix(in srgb, var(--sys-color-warning-text) 12%, transparent);
  color: var(--sys-color-warning-text);
  font: var(--sys-typography-caption);
  padding: 0.2rem 0.4rem;
}

.workflow-node__alert button {
  margin-inline-start: auto;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font: var(--sys-typography-caption);
  padding: 0;
  text-decoration: underline;
}

.workflow-node__content {
  display: grid;
  gap: 0.45rem;
}

.workflow-node__header {
  display: flex;
  align-items: center;
  justify-content: center;
  min-inline-size: 0;
  gap: var(--sys-space-2);
}

.workflow-node__title {
  overflow: hidden;
  font: var(--sys-typography-label-strong);
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-node__kind {
  flex: 0 0 auto;
  border-radius: 999px;
  background: color-mix(
    in srgb,
    var(--node-accent, var(--sys-color-action-primary)) 16%,
    transparent
  );
  color: var(--node-accent, var(--sys-color-action-primary));
  font: var(--sys-typography-label);
  padding: 0.1rem 0.38rem;
}

.workflow-node__description {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  line-height: 1.35;
  text-align: center;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.workflow-node__parameters {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 0.3rem;
}

.workflow-node__parameter {
  max-inline-size: 100%;
  overflow: hidden;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.35rem;
  background: var(--sys-color-surface-muted);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  padding: 0.12rem 0.38rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-node__footer {
  display: flex;
  min-inline-size: 0;
  align-items: center;
  justify-content: center;
  gap: var(--sys-space-2);
}

.workflow-node__adapter,
.workflow-node__return {
  display: inline-flex;
  align-items: center;
  color: var(--sys-color-text-subtle);
  font: var(--sys-typography-caption);
  gap: 0.25rem;
}

.workflow-node__adapter {
  color: var(--sys-color-warning-text);
}

.workflow-node__adapter.is-ready {
  color: var(--sys-color-success-text);
}

.workflow-node__return code {
  color: var(--sys-color-text-muted);
  font-family: var(--ref-font-mono);
  font-size: 0.72rem;
}

.workflow-node__port {
  position: absolute;
  z-index: 2;
  inline-size: 0.88rem;
  block-size: 0.88rem;
  border: 0.18rem solid currentcolor;
  border-radius: 50%;
  background: var(--sys-color-field);
  color: var(--sys-color-action-primary);
  cursor: crosshair;
  transform: translateY(-50%);
  transition:
    background var(--sys-motion-fast),
    transform var(--sys-motion-fast);
}

.workflow-node:hover .workflow-node__port,
.workflow-node__port.is-drawing {
  transform: translateY(-50%) scale(1.2);
}

.workflow-node__port--input {
  inset-block-start: 50%;
  inset-inline-start: -0.5rem;
  cursor: default;
}

.workflow-node__port--success {
  inset-block-start: 50%;
  inset-inline-end: -0.5rem;
  color: var(--sys-color-success-text);
}

.workflow-node.is-branch .workflow-node__port--success {
  inset-block-start: 36%;
}

.workflow-node__port--failure {
  inset-block-start: 74%;
  inset-inline-end: -0.5rem;
  color: var(--sys-color-danger);
}

.workflow-node__port--success:hover,
.workflow-node__port--success.is-drawing {
  background: var(--sys-color-success-text);
}

.workflow-node__port--failure:hover {
  background: var(--sys-color-danger);
}

@media (prefers-reduced-motion: reduce) {
  .workflow-node__body,
  .workflow-node__port {
    transition: none;
  }
}
</style>
