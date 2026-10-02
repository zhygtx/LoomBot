<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Trash2, X } from '@lucide/vue'

import type { ConnectionRecord } from '@modules/connections'

import { BaseButton, BaseNotice } from '@shared/ui'

import type {
  WorkflowEdge,
  WorkflowNode,
  WorkflowNodeInput,
  WorkflowNodeParameter,
  WorkflowReturnField,
} from '../api/workflow-api'
import {
  inputConfigured,
  isEventNode,
  nodeLabel,
  parameterLabel,
  sortParameters,
} from '../model/graph'

const props = defineProps<{
  node: WorkflowNode
  connections: ConnectionRecord[]
  allNodes: WorkflowNode[]
  allEdges: WorkflowEdge[]
}>()

const emit = defineEmits<{
  close: []
  save: [node: WorkflowNode]
  remove: [node: WorkflowNode]
}>()

const draft = ref<WorkflowNode>(cloneNode(props.node))
const inputModes = reactive<Record<string, 'source' | 'default'>>({})
const isAdapter = computed(() => Boolean(draft.value.connectionType))
const isSchedule = computed(() => draft.value.nodeKey === 'system.schedule')
const isEvent = computed(() => isEventNode(draft.value))
// 事件节点的参数由平台事件提供，不入画布配置；定时任务另有「定时配置」区块。
const parameters = computed(() =>
  isEvent.value ? [] : sortParameters(draft.value.descriptor?.parameters),
)
const adapterConnections = computed(() =>
  props.connections.filter(
    (connection) =>
      connection.pluginVersionId === draft.value.pluginVersionId &&
      connection.connectionType === draft.value.connectionType,
  ),
)
const ancestorNodes = computed(() => {
  const result: WorkflowNode[] = []
  const visited = new Set([draft.value.id])
  const queue = [draft.value.id]
  while (queue.length) {
    const current = queue.shift()
    if (!current) continue
    for (const edge of props.allEdges) {
      if (edge.to !== current || visited.has(edge.from)) continue
      visited.add(edge.from)
      const source = props.allNodes.find((node) => node.id === edge.from)
      if (source) {
        result.push(source)
        queue.push(source.id)
      }
    }
  }
  return result
})
const selectedConnectionMissing = computed(
  () =>
    Boolean(draft.value.connectionId) &&
    !adapterConnections.value.some((connection) => connection.id === draft.value.connectionId),
)

watch(
  () => props.node.id,
  () => {
    draft.value = cloneNode(props.node)
    resetInputModes()
  },
)

function cloneNode(node: WorkflowNode): WorkflowNode {
  return JSON.parse(JSON.stringify(node)) as WorkflowNode
}

function inputOf(paramName: string): WorkflowNodeInput {
  draft.value.inputs ??= []
  let input = draft.value.inputs.find((item) => item.paramName === paramName)
  if (!input) {
    input = { paramName, source: '', defaultValue: null }
    draft.value.inputs.push(input)
  }
  return input
}

function resetInputModes(): void {
  Object.keys(inputModes).forEach((key) => delete inputModes[key])
  for (const input of draft.value.inputs ?? []) {
    inputModes[input.paramName] = input.source ? 'source' : 'default'
  }
}

function inputMode(input: WorkflowNodeInput): 'source' | 'default' {
  return inputModes[input.paramName] ?? (input.source ? 'source' : 'default')
}

function setInputMode(input: WorkflowNodeInput, mode: 'source' | 'default'): void {
  inputModes[input.paramName] = mode
  input.source = mode === 'source' ? input.source || '' : ''
  if (mode === 'default' && input.defaultValue === undefined) input.defaultValue = null
}

function sourceNodeId(input: WorkflowNodeInput): string {
  const nodeId = String(input.source || '').split('.')[0] || ''
  return ancestorNodes.value.some((node) => node.id === nodeId) ? nodeId : ''
}

function sourceFieldPath(input: WorkflowNodeInput): string {
  const nodeId = sourceNodeId(input)
  if (!nodeId) return ''
  const source = String(input.source || '')
  if (source === nodeId) return '__whole__'
  return source.startsWith(`${nodeId}.`) ? source.slice(nodeId.length + 1) : ''
}

function sourceFieldOptions(nodeId: string): WorkflowReturnField[] {
  const sourceNode = props.allNodes.find((node) => node.id === nodeId)
  const fields = sourceNode?.descriptor?.returnFields ?? []
  if (fields.length) return fields
  return [
    {
      name: '整个返回值',
      type: sourceNode?.descriptor?.returnType || 'object',
      path: '',
      description: '',
      depth: 0,
    },
  ]
}

function sourceFieldValue(field: WorkflowReturnField): string {
  return field.path ? field.path : '__whole__'
}

function sourceFieldLabel(field: WorkflowReturnField): string {
  const indent = '　'.repeat(Math.max(0, field.depth ?? 0))
  const path = field.path || '整个返回值'
  const displayName = field.displayName || field.name || field.key || path
  const label = path !== displayName && field.path ? `${displayName} (${path})` : displayName
  const type = field.type ? ` · ${field.type}` : ''
  const description = field.description ? ` · ${field.description}` : ''
  return `${indent}${label}${type}${description}`
}

function hasSourceField(input: WorkflowNodeInput): boolean {
  const current = sourceFieldPath(input)
  if (!current) return true
  return sourceFieldOptions(sourceNodeId(input)).some(
    (field) => sourceFieldValue(field) === current,
  )
}

function setSourceNode(input: WorkflowNodeInput, nodeId: string): void {
  input.source = nodeId
}

function setSourceField(input: WorkflowNodeInput, value: string): void {
  const nodeId = sourceNodeId(input)
  if (!nodeId) return
  input.source = value === '__whole__' ? nodeId : `${nodeId}.${value}`
}

function defaultValue(input: WorkflowNodeInput): string {
  const value = input.defaultValue
  if (value === null || value === undefined) return ''
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function updateDefault(input: WorkflowNodeInput, value: string): void {
  input.defaultValue = value
}

function updateNumberDefault(input: WorkflowNodeInput, value: string): void {
  input.defaultValue = value === '' ? null : Number(value)
}

function updateBooleanDefault(input: WorkflowNodeInput, value: string): void {
  input.defaultValue = value === '' ? null : value === 'true'
}

function inputType(parameter: WorkflowNodeParameter): 'number' | 'text' {
  const type = String(parameter.type ?? '').toLowerCase()
  return ['int', 'integer', 'long', 'short', 'float', 'double', 'decimal'].includes(type)
    ? 'number'
    : 'text'
}

function isBoolean(parameter: WorkflowNodeParameter): boolean {
  return ['bool', 'boolean'].includes(String(parameter.type ?? '').toLowerCase())
}

function setCron(value: string): void {
  draft.value.config = { ...(draft.value.config ?? {}), cron: value }
}

function connectionLabel(connection: ConnectionRecord): string {
  return `${connection.name} · ${connection.connectionType}${connection.enabled ? '' : '（已停用）'}`
}

function save(): void {
  emit('save', draft.value)
}
</script>

<template>
  <aside class="workflow-inspector" @wheel.stop>
    <header class="workflow-inspector__header">
      <div>
        <strong>{{ nodeLabel(draft) }}</strong>
        <small>{{ draft.descriptor?.description || draft.nodeKey }}</small>
      </div>
      <button
        type="button"
        class="workflow-inspector__icon-button"
        aria-label="关闭配置"
        @click="$emit('close')"
      >
        <X :size="18" />
      </button>
    </header>

    <div class="workflow-inspector__body">
      <BaseNotice v-if="isAdapter" tone="warning" title="协议适配器节点">
        事件和动作都必须绑定连接，下拉框只显示当前插件管理的连接。
      </BaseNotice>

      <section v-if="isAdapter" class="workflow-inspector__section">
        <h3>连接绑定</h3>
        <label class="workflow-inspector__field">
          <span>选择连接 <em>*</em></span>
          <select v-model="draft.connectionId">
            <option value="">请选择当前插件下的连接</option>
            <option v-if="selectedConnectionMissing" :value="draft.connectionId" disabled>
              当前连接不可用（ID {{ draft.connectionId }}）
            </option>
            <option
              v-for="connection in adapterConnections"
              :key="connection.id"
              :value="connection.id"
              :disabled="!connection.enabled"
            >
              {{ connectionLabel(connection) }}
            </option>
          </select>
          <small>
            插件：{{ draft.descriptor?.name || draft.nodeKey }} · 连接类型：{{
              draft.connectionType
            }}
          </small>
        </label>
        <p v-if="!adapterConnections.length" class="workflow-inspector__empty">
          还没有为这个插件创建可用连接。
        </p>
      </section>

      <section v-if="isSchedule" class="workflow-inspector__section">
        <h3>定时配置</h3>
        <label class="workflow-inspector__field">
          <span>Cron 表达式 <em>*</em></span>
          <input
            :value="String(draft.config?.cron ?? '')"
            placeholder="0 0 8 * * ?"
            @input="setCron(($event.target as HTMLInputElement).value)"
          />
          <small>支持 5 或 6 字段，例如 0 0 8 * * ?</small>
        </label>
      </section>

      <section class="workflow-inspector__section">
        <header class="workflow-inspector__section-head">
          <h3>输入参数</h3>
          <span>{{ parameters.length }}</span>
        </header>

        <p v-if="!parameters.length" class="workflow-inspector__empty">该节点没有输入参数</p>
        <div
          v-for="parameter in parameters"
          :key="parameter.name"
          class="workflow-inspector__parameter"
        >
          <div class="workflow-inspector__parameter-head">
            <strong>
              {{ parameterLabel(parameter) }}
              <em v-if="parameter.required">*</em>
            </strong>
            <code>{{ parameter.type || 'object' }}</code>
          </div>
          <p v-if="parameter.description">{{ parameter.description }}</p>

          <div class="workflow-inspector__mode-row">
            <select
              :value="inputMode(inputOf(parameter.name))"
              @change="
                setInputMode(
                  inputOf(parameter.name),
                  ($event.target as HTMLSelectElement).value as 'source' | 'default',
                )
              "
            >
              <option value="source">引用</option>
              <option value="default">默认值</option>
            </select>

            <div
              v-if="inputMode(inputOf(parameter.name)) === 'source'"
              class="workflow-inspector__source-picker"
            >
              <select
                :value="sourceNodeId(inputOf(parameter.name))"
                @change="
                  setSourceNode(
                    inputOf(parameter.name),
                    ($event.target as HTMLSelectElement).value,
                  )
                "
              >
                <option value="">先选择前置节点</option>
                <option v-for="sourceNode in ancestorNodes" :key="sourceNode.id" :value="sourceNode.id">
                  {{ nodeLabel(sourceNode) }}
                </option>
              </select>
              <select
                :value="sourceFieldPath(inputOf(parameter.name))"
                :disabled="!sourceNodeId(inputOf(parameter.name))"
                @change="
                  setSourceField(
                    inputOf(parameter.name),
                    ($event.target as HTMLSelectElement).value,
                  )
                "
              >
                <option value="">再选择返回值字段</option>
                <option
                  v-if="!hasSourceField(inputOf(parameter.name))"
                  :value="sourceFieldPath(inputOf(parameter.name))"
                  disabled
                >
                  当前字段已不可用
                </option>
                <option
                  v-for="field in sourceFieldOptions(sourceNodeId(inputOf(parameter.name)))"
                  :key="sourceFieldValue(field)"
                  :value="sourceFieldValue(field)"
                >
                  {{ sourceFieldLabel(field) }}
                </option>
              </select>
              <small v-if="!ancestorNodes.length">当前节点还没有可用的前置节点。</small>
            </div>

            <select
              v-else-if="isBoolean(parameter)"
              :value="String(inputOf(parameter.name).defaultValue)"
              @change="
                updateBooleanDefault(
                  inputOf(parameter.name),
                  ($event.target as HTMLSelectElement).value,
                )
              "
            >
              <option value="">请选择</option>
              <option value="true">true</option>
              <option value="false">false</option>
            </select>

            <input
              v-else-if="inputType(parameter) === 'number'"
              :value="defaultValue(inputOf(parameter.name))"
              type="number"
              placeholder="请输入数字"
              @input="
                updateNumberDefault(
                  inputOf(parameter.name),
                  ($event.target as HTMLInputElement).value,
                )
              "
            />

            <input
              v-else
              :value="defaultValue(inputOf(parameter.name))"
              :placeholder="parameter.description || '请输入默认值'"
              @input="
                updateDefault(inputOf(parameter.name), ($event.target as HTMLInputElement).value)
              "
            />
          </div>

          <small
            v-if="parameter.required && !inputConfigured(inputOf(parameter.name))"
            class="workflow-inspector__param-warning"
          >
            必填：请选择引用或填写默认值
          </small>
        </div>
      </section>

      <section class="workflow-inspector__section">
        <header class="workflow-inspector__section-head">
          <h3>分支设置</h3>
          <span>{{ draft.branch ? '已开启' : '关闭' }}</span>
        </header>
        <label class="workflow-inspector__check">
          <input
            type="checkbox"
            :checked="Boolean(draft.branch)"
            @change="
              draft.branch = ($event.target as HTMLInputElement).checked
                ? { mode: 'truthy', expression: '' }
                : null
            "
          />
          <span>作为分支节点</span>
        </label>
        <div v-if="draft.branch" class="workflow-inspector__branch">
          <select v-model="draft.branch.mode">
            <option value="truthy">按真值判定</option>
            <option value="expression">按表达式判定</option>
          </select>
          <input
            v-if="draft.branch.mode === 'expression'"
            v-model="draft.branch.expression"
            placeholder="例如 X >= 1000"
          />
          <small>分支开启后会显示成功和失败两个输出口。</small>
        </div>
      </section>
    </div>

    <footer class="workflow-inspector__footer">
      <BaseButton appearance="danger" size="small" @click="$emit('remove', draft)">
        <Trash2 :size="15" />
        删除节点
      </BaseButton>
      <div>
        <BaseButton appearance="secondary" size="small" @click="$emit('close')">取消</BaseButton>
        <BaseButton size="small" @click="save">保存</BaseButton>
      </div>
    </footer>
  </aside>
</template>

<style scoped>
.workflow-inspector {
  position: absolute;
  z-index: 40;
  inset-block: 6.25rem 1rem;
  inset-inline-end: 1rem;
  display: flex;
  inline-size: min(23rem, calc(100vw - 2rem));
  min-block-size: 0;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--sys-color-border) 78%, transparent);
  border-radius: 0.75rem;
  background: color-mix(in srgb, var(--sys-color-surface-raised) 97%, transparent);
  box-shadow: var(--sys-shadow-raised);
  backdrop-filter: blur(1rem);
}

.workflow-inspector__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  border-block-end: 1px solid var(--sys-color-border);
  gap: var(--sys-space-3);
  padding: var(--sys-space-4);
}

.workflow-inspector__header strong,
.workflow-inspector__header small {
  display: block;
}

.workflow-inspector__header strong {
  font: var(--sys-typography-label-strong);
}

.workflow-inspector__header small {
  margin-block-start: 0.2rem;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-inspector__icon-button {
  display: grid;
  inline-size: 2rem;
  block-size: 2rem;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--sys-color-border);
  border-radius: 50%;
  background: var(--sys-color-field);
  color: var(--sys-color-text-muted);
  cursor: pointer;
}

.workflow-inspector__icon-button:hover {
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-text);
}

.workflow-inspector__body {
  display: grid;
  min-block-size: 0;
  flex: 1;
  align-content: start;
  gap: var(--sys-space-4);
  overflow-y: auto;
  padding: var(--sys-space-4);
}

.workflow-inspector__section {
  display: grid;
  gap: var(--sys-space-3);
  border-block-start: 1px solid var(--sys-color-border);
  padding-block-start: var(--sys-space-4);
}

.workflow-inspector__section:first-of-type {
  border-block-start: 0;
  padding-block-start: 0;
}

.workflow-inspector__section h3,
.workflow-inspector__section p {
  margin: 0;
}

.workflow-inspector__section h3 {
  font: var(--sys-typography-label-strong);
}

.workflow-inspector__section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.workflow-inspector__section-head > span {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-inspector__field {
  display: grid;
  gap: var(--sys-space-2);
}

.workflow-inspector__field > span {
  font: var(--sys-typography-label-strong);
}

.workflow-inspector__field em,
.workflow-inspector__parameter em {
  color: var(--sys-color-danger);
  font-style: normal;
}

.workflow-inspector__field small,
.workflow-inspector__branch small {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-inspector input,
.workflow-inspector select {
  min-inline-size: 0;
  inline-size: 100%;
  min-block-size: 2.35rem;
  border: 1px solid var(--sys-color-border-strong);
  border-radius: 0.6rem;
  outline: 0;
  background: var(--sys-color-field);
  color: var(--sys-color-text);
  font: var(--sys-typography-body-compact);
  padding: 0 var(--sys-space-3);
}

.workflow-inspector input:focus,
.workflow-inspector select:focus {
  border-color: var(--sys-color-action-primary);
  box-shadow: 0 0 0 var(--sys-focus-width) var(--sys-color-focus);
}

.workflow-inspector__parameter {
  display: grid;
  gap: var(--sys-space-2);
  border: 1px solid var(--sys-color-border);
  border-radius: 0.65rem;
  background: color-mix(in srgb, var(--sys-color-surface-muted) 55%, transparent);
  padding: var(--sys-space-3);
}

.workflow-inspector__parameter-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sys-space-2);
}

.workflow-inspector__parameter-head strong {
  font: var(--sys-typography-label-strong);
}

.workflow-inspector__parameter-head code {
  border-radius: 999px;
  background: var(--sys-color-surface-raised);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  padding: 0.12rem 0.45rem;
}

.workflow-inspector__parameter p {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-inspector__mode-row {
  display: grid;
  grid-template-columns: 5.6rem minmax(0, 1fr);
  gap: var(--sys-space-2);
}

.workflow-inspector__source-picker {
  display: grid;
  gap: var(--sys-space-2);
}

.workflow-inspector__source-picker select:disabled {
  cursor: not-allowed;
  opacity: var(--sys-opacity-disabled);
}

.workflow-inspector__check {
  display: flex;
  align-items: center;
  color: var(--sys-color-text);
  cursor: pointer;
  font: var(--sys-typography-body-compact);
  gap: var(--sys-space-2);
}

.workflow-inspector__check input {
  inline-size: 1rem;
  min-block-size: 1rem;
  accent-color: var(--sys-color-action-primary);
}

.workflow-inspector__branch {
  display: grid;
  gap: var(--sys-space-2);
}

.workflow-inspector__empty {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-inspector__param-warning {
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-caption);
}

.workflow-inspector__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-block-start: 1px solid var(--sys-color-border);
  gap: var(--sys-space-3);
  padding: var(--sys-space-3) var(--sys-space-4);
}

.workflow-inspector__footer > div {
  display: flex;
  gap: var(--sys-space-2);
}

@media (max-width: 56rem) {
  .workflow-inspector {
    inset-block: auto 0.75rem;
    inset-inline: 0.75rem;
    inline-size: auto;
    max-block-size: min(34rem, calc(100dvh - 6rem));
  }
}
</style>
