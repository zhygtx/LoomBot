<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { Copy, Power, PowerOff, RefreshCw, Server, Trash2, WandSparkles } from '@lucide/vue'

import { ApiError } from '@shared/api/http-client'
import { hasPermission } from '@shared/session/permissions'
import { BaseButton, BaseField, BaseNotice, BaseSurface, BaseSwitch, message } from '@shared/ui'

import { useSessionStore } from '../../auth/model/session-store'
import { connectionsApi } from '../api/connections-api'
import type {
  ConnectionRecord,
  ConnectionTypeDescriptor,
  JsonSchemaNode,
} from '../model/types'

const types = ref<ConnectionTypeDescriptor[]>([])
const connections = ref<ConnectionRecord[]>([])
const session = useSessionStore()
const loading = ref(false)
const loadingTypes = ref(false)
const creating = ref(false)
const busyIds = ref<Set<string>>(new Set())
const selectedTypeKey = ref('')
const formErrors = reactive<Record<string, string>>({})
const form = reactive({
  name: '',
  remark: '',
  config: {} as Record<string, unknown>,
})
const jsonInputs = reactive<Record<string, string>>({})
const lastCreated = ref<ConnectionRecord | null>(null)
const lastCreatedConfig = ref<Record<string, unknown>>({})
let refreshTimer: number | undefined
let initialized = false

const canList = computed(() => hasPermission(session.user?.permissions, 'connection:ws:list'))
const canCreate = computed(() => hasPermission(session.user?.permissions, 'connection:ws:create'))
const canOperate = computed(() => hasPermission(session.user?.permissions, 'connection:ws:operate'))
const canDelete = computed(() => hasPermission(session.user?.permissions, 'connection:ws:delete'))

const selectedType = computed(
  () => types.value.find((item) => typeKey(item) === selectedTypeKey.value) ?? null,
)

const lastCreatedType = computed(() => {
  const created = lastCreated.value
  if (!created) return null
  return (
    types.value.find(
      (item) =>
        item.pluginVersionId === created.pluginVersionId && item.type === created.connectionType,
    ) ?? null
  )
})

const fields = computed(() => {
  const properties = selectedType.value?.configSchema.properties ?? {}
  return Object.entries(properties)
    .map(([name, schema]) => ({ name, schema }))
    .sort(
      (left, right) => valueOf(left.schema['x-order'], 999) - valueOf(right.schema['x-order'], 999),
    )
})

const createdFields = computed(() => {
  const properties = lastCreatedType.value?.configSchema.properties ?? {}
  return Object.entries(properties)
    .map(([name, schema]) => ({ name, schema, value: createdFieldValue(name) }))
    .filter((field) => field.value !== '' && field.schema.type !== 'boolean')
    .sort(
      (left, right) => valueOf(left.schema['x-order'], 999) - valueOf(right.schema['x-order'], 999),
    )
})

function valueOf(value: unknown, fallback: number): number {
  return typeof value === 'number' ? value : fallback
}

function createdFieldValue(name: string): string {
  const raw = lastCreatedConfig.value[name] ?? lastCreated.value?.config?.[name]
  if (raw === null || raw === undefined) return ''
  return typeof raw === 'string' ? raw : JSON.stringify(raw)
}

function typeKey(item: ConnectionTypeDescriptor): string {
  return `${item.pluginVersionId}:${item.type}`
}

function typeLabel(item: ConnectionTypeDescriptor): string {
  const direction = item.direction === 'REVERSE' ? '反向接入' : '正向连接'
  const displayName = item.displayName && item.displayName !== item.pluginName ? item.displayName : item.type
  return `${item.pluginName} · ${item.pluginVersion} · ${displayName} · ${direction}`
}

function configValue(name: string): unknown {
  return form.config[name]
}

function stringValue(name: string): string {
  const value = configValue(name)
  return value === null || value === undefined ? '' : String(value)
}

function numberValue(name: string): string {
  const value = configValue(name)
  return typeof value === 'number' ? String(value) : ''
}

function booleanValue(name: string): boolean {
  return configValue(name) === true
}

function setValue(name: string, value: unknown): void {
  form.config[name] = value
}

function setNumberValue(name: string, value: string): void {
  setValue(name, value === '' ? null : Number(value))
}

function jsonValue(name: string): string {
  return jsonInputs[name] ?? '{}'
}

function setJsonValue(name: string, value: string): void {
  jsonInputs[name] = value
}

function isRequired(name: string): boolean {
  return selectedType.value?.configSchema.required?.includes(name) ?? false
}

function fieldType(schema: JsonSchemaNode): 'password' | 'text' {
  return schema['x-widget'] === 'password' ? 'password' : 'text'
}

function generateValue(name: string): void {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  const value = [...bytes].map((item) => item.toString(16).padStart(2, '0')).join('')
  setValue(name, value)
}

function onTypeChanged(): void {
  form.config = {}
  Object.keys(jsonInputs).forEach((key) => delete jsonInputs[key])
  const descriptor = selectedType.value
  if (!descriptor) return
  for (const [name, schema] of Object.entries(descriptor.configSchema.properties ?? {})) {
    if (schema.default !== undefined) {
      form.config[name] = schema.default
    } else if (schema.type === 'boolean') {
      form.config[name] = false
    } else if (schema.type === 'integer' || schema.type === 'number') {
      form.config[name] = null
    } else if (schema.type === 'object' || schema.type === 'array') {
      form.config[name] = schema.type === 'array' ? [] : {}
      jsonInputs[name] = JSON.stringify(form.config[name], null, 2)
    } else {
      form.config[name] = ''
    }
  }
}

async function loadTypes(): Promise<void> {
  if (!canList.value || loadingTypes.value) return
  loadingTypes.value = true
  try {
    const typeList = await connectionsApi.listTypes()
    types.value = typeList
    if (
      !selectedTypeKey.value ||
      !typeList.some((item) => typeKey(item) === selectedTypeKey.value)
    ) {
      selectedTypeKey.value = typeList[0] ? typeKey(typeList[0]) : ''
    }
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '适配器插件列表加载失败')
  } finally {
    loadingTypes.value = false
  }
}

async function loadConnections(): Promise<void> {
  if (!canList.value) return
  loading.value = true
  try {
    const page = await connectionsApi.list()
    connections.value = page.records
    if (lastCreated.value) {
      lastCreated.value = page.records.find((item) => item.id === lastCreated.value?.id) ?? null
    }
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '连接列表加载失败')
  } finally {
    loading.value = false
  }
}

async function refresh(): Promise<void> {
  await Promise.all([loadTypes(), loadConnections()])
}

function validateForm(): boolean {
  Object.keys(formErrors).forEach((key) => delete formErrors[key])
  if (!form.name.trim()) formErrors.name = '请输入连接名'
  if (!selectedType.value) formErrors.type = '请选择适配器类型'
  for (const field of fields.value) {
    if (!isRequired(field.name)) continue
    const value = form.config[field.name]
    if (value === null || value === undefined || value === '') {
      formErrors[field.name] = '此项必填'
    }
  }
  return Object.keys(formErrors).length === 0
}

function buildConfig(): Record<string, unknown> {
  const result: Record<string, unknown> = {}
  for (const field of fields.value) {
    const value = form.config[field.name]
    if (field.schema.type === 'object' || field.schema.type === 'array') {
      const raw = jsonInputs[field.name] ?? JSON.stringify(value ?? {})
      try {
        result[field.name] = JSON.parse(raw)
      } catch {
        formErrors[field.name] = 'JSON 格式不正确'
        throw new Error('invalid-json')
      }
    } else if (value !== null && value !== undefined && value !== '') {
      result[field.name] = value
    }
  }
  return result
}

async function createConnection(): Promise<void> {
  if (!validateForm() || !selectedType.value) return
  const descriptor = selectedType.value
  creating.value = true
  try {
    const config = buildConfig()
    const created = await connectionsApi.create({
      name: form.name.trim(),
      pluginVersionId: descriptor.pluginVersionId,
      connectionType: descriptor.type,
      config,
      remark: form.remark.trim() || null,
    })
    connections.value.unshift(created)
    lastCreatedConfig.value = { ...config }
    lastCreated.value = created
    form.name = ''
    form.remark = ''
    message.success('连接已创建')
  } catch (error) {
    if (error instanceof Error && error.message === 'invalid-json') return
    message.error(error instanceof ApiError ? error.message : '创建连接失败')
  } finally {
    creating.value = false
  }
}

async function setEnabled(record: ConnectionRecord, enabled: boolean): Promise<void> {
  busyIds.value = new Set(busyIds.value).add(record.id)
  try {
    const updated = enabled
      ? await connectionsApi.enable(record.id)
      : await connectionsApi.disable(record.id)
    replaceConnection(updated)
    message.success(enabled ? '连接已启用' : '连接已停用')
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '操作失败')
  } finally {
    const next = new Set(busyIds.value)
    next.delete(record.id)
    busyIds.value = next
  }
}

async function removeConnection(record: ConnectionRecord): Promise<void> {
  if (!window.confirm(`确定删除连接「${record.name}」吗？`)) return
  busyIds.value = new Set(busyIds.value).add(record.id)
  try {
    await connectionsApi.remove(record.id)
    connections.value = connections.value.filter((item) => item.id !== record.id)
    if (lastCreated.value?.id === record.id) lastCreated.value = null
    message.success('连接已删除')
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '删除失败')
  } finally {
    const next = new Set(busyIds.value)
    next.delete(record.id)
    busyIds.value = next
  }
}

function replaceConnection(updated: ConnectionRecord): void {
  const index = connections.value.findIndex((item) => item.id === updated.id)
  if (index >= 0) connections.value.splice(index, 1, updated)
}

async function copy(value: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(value)
    message.success('已复制')
  } catch {
    message.error('复制失败，请手动选择文本')
  }
}

function statusLabel(record: ConnectionRecord): string {
  const status = record.status
  const state = status?.state ?? 'PENDING'
  return (
    {
      DISABLED: '已停用',
      PENDING: '等待应用',
      STARTING: '启动中',
      CONNECTING: '连接中',
      LISTENING: '等待平台接入',
      ONLINE: '在线',
      RETRY_WAIT: '等待重试',
      DEGRADED: '降级运行',
      STOPPING: '停止中',
      FAILED: '失败',
    }[state] ?? state
  )
}

function statusTone(record: ConnectionRecord): string {
  const status = record.status
  const state = status?.state
  if (state === 'ONLINE') return 'online'
  if (state === 'FAILED') return 'danger'
  if (
    state === 'PENDING' ||
    state === 'STARTING' ||
    state === 'CONNECTING' ||
    state === 'RETRY_WAIT' ||
    state === 'DEGRADED' ||
    state === 'STOPPING'
  )
    return 'warning'
  return 'muted'
}

function runtimeUnavailable(record: ConnectionRecord): boolean {
  return Boolean(record.enabled && record.status?.lastSyncedAt && !record.status.runtimeReachable)
}

onMounted(() => {
  refreshTimer = window.setInterval(() => void loadConnections(), 5000)
})

// 整页刷新时子页面先挂载，AppShell 可能还没加载完用户权限；
// 等 canList 变为 true 后再执行第一次目录和连接列表查询。
watch(
  canList,
  (value) => {
    if (!value || initialized) return
    initialized = true
    void refresh()
  },
  { immediate: true },
)

onUnmounted(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
})
</script>

<template>
  <div class="connections-page">
    <header class="connections-page__header">
      <div>
        <p class="connections-page__eyebrow">Connection lab</p>
        <h1>连接测试台</h1>
        <p>用插件仓库里的真实版本创建连接，验证反向接入和正向 Gateway。</p>
      </div>
      <div class="connections-page__actions">
        <BaseButton
          appearance="secondary"
          :loading="loading || loadingTypes"
          :disabled="!canList"
          @click="refresh"
        >
          <RefreshCw :size="16" />
          刷新
        </BaseButton>
      </div>
    </header>

    <div
      class="connections-page__layout"
      :class="{ 'connections-page__layout--single': !canCreate }"
    >
      <BaseSurface v-if="canCreate" class="connection-form" padding="medium">
        <div class="connection-form__title">
          <Server :size="18" />
          <div>
            <h2>新建连接</h2>
            <p>配置字段由所选插件版本的 connection-schema 动态生成。</p>
          </div>
        </div>

        <label class="connection-field">
          <span class="connection-field__label">适配器类型</span>
          <select
            v-model="selectedTypeKey"
            class="connection-field__select"
            :disabled="!types.length"
            @change="onTypeChanged"
          >
            <option value="" disabled>
              {{ types.length ? '请选择适配器类型' : '未发现适配器插件' }}
            </option>
            <option v-for="item in types" :key="typeKey(item)" :value="typeKey(item)">
              {{ typeLabel(item) }}
            </option>
          </select>
          <span v-if="formErrors.type" class="connection-field__error">{{ formErrors.type }}</span>
        </label>

        <BaseField
          v-model="form.name"
          name="connection-name"
          label="连接名"
          placeholder="例如 napcat-main"
          required
          :error="formErrors.name"
        />

        <div v-if="selectedType" class="connection-form__schema">
          <div class="connection-form__schema-head">
            <strong>{{ selectedType.displayName }}</strong>
            <span>{{ selectedType.pluginKey }}@{{ selectedType.pluginVersion }}</span>
          </div>

          <template v-for="field in fields" :key="field.name">
            <label v-if="field.schema.enum" class="connection-field">
              <span class="connection-field__label">
                {{ field.schema.title || field.name }}
                <b v-if="isRequired(field.name)">*</b>
              </span>
              <select
                class="connection-field__select"
                :value="stringValue(field.name)"
                @change="setValue(field.name, ($event.target as HTMLSelectElement).value)"
              >
                <option v-for="option in field.schema.enum" :key="option" :value="option">
                  {{ option }}
                </option>
              </select>
              <span v-if="field.schema.description" class="connection-field__hint">
                {{ field.schema.description }}
              </span>
              <span v-if="formErrors[field.name]" class="connection-field__error">
                {{ formErrors[field.name] }}
              </span>
            </label>

            <div
              v-else-if="field.schema.type === 'boolean'"
              class="connection-field connection-field--switch"
            >
              <div>
                <span class="connection-field__label">{{ field.schema.title || field.name }}</span>
                <span v-if="field.schema.description" class="connection-field__hint">
                  {{ field.schema.description }}
                </span>
              </div>
              <BaseSwitch
                :model-value="booleanValue(field.name)"
                @update:model-value="setValue(field.name, $event)"
              />
            </div>

            <BaseField
              v-else-if="field.schema.type === 'object' || field.schema.type === 'array'"
              :model-value="jsonValue(field.name)"
              :name="field.name"
              :label="field.schema.title || field.name"
              :hint="field.schema.description"
              :error="formErrors[field.name]"
              type="text"
              @update:model-value="setJsonValue(field.name, $event)"
            />

            <BaseField
              v-else
              :model-value="
                field.schema.type === 'integer' || field.schema.type === 'number'
                  ? numberValue(field.name)
                  : stringValue(field.name)
              "
              :name="field.name"
              :label="field.schema.title || field.name"
              :hint="field.schema.description"
              :error="formErrors[field.name]"
              :required="isRequired(field.name)"
              :type="fieldType(field.schema)"
              :revealable="Boolean(field.schema['x-secret'])"
              @update:model-value="
                field.schema.type === 'integer' || field.schema.type === 'number'
                  ? setNumberValue(field.name, $event)
                  : setValue(field.name, $event)
              "
            >
              <template v-if="field.schema['x-generate']" #suffix>
                <button
                  class="connection-field__generate"
                  type="button"
                  title="生成随机值"
                  @click="generateValue(field.name)"
                >
                  <WandSparkles :size="16" />
                </button>
              </template>
            </BaseField>
          </template>
        </div>

        <BaseField v-model="form.remark" name="connection-remark" label="备注" placeholder="可选" />

        <BaseButton :loading="creating" @click="createConnection">创建并启用</BaseButton>
      </BaseSurface>

      <section class="connection-list">
        <div class="connection-list__head">
          <div>
            <h2>已创建连接</h2>
            <p>{{ connections.length }} 条，每 5 秒刷新运行状态。</p>
          </div>
        </div>

        <article v-for="record in connections" :key="record.id" class="connection-item">
          <div class="connection-item__main">
            <div class="connection-item__name-row">
              <strong>{{ record.name }}</strong>
              <span class="connection-state" :class="`connection-state--${statusTone(record)}`">
                {{ statusLabel(record) }}
              </span>
            </div>
            <p>{{ record.connectionType }} · 插件版本 {{ record.pluginVersionId }}</p>
            <code v-if="record.endpointUrl">{{ record.endpointUrl }}</code>
            <p v-if="runtimeUnavailable(record)" class="connection-item__error">
              适配器监管器不可达，显示的是最近一次状态
            </p>
            <p v-else-if="record.status?.revisionPending" class="connection-item__pending">
              配置正在应用
            </p>
            <p v-if="record.status?.failureReason" class="connection-item__error">
              {{ record.status.failureReason }}
            </p>
          </div>

          <div class="connection-item__actions">
            <BaseButton
              v-if="canOperate"
              size="small"
              appearance="secondary"
              :loading="busyIds.has(record.id)"
              @click="setEnabled(record, !record.enabled)"
            >
              <component :is="record.enabled ? PowerOff : Power" :size="15" />
              {{ record.enabled ? '停用' : '启用' }}
            </BaseButton>
            <BaseButton
              v-if="canDelete"
              size="small"
              appearance="danger"
              :loading="busyIds.has(record.id)"
              @click="removeConnection(record)"
            >
              <Trash2 :size="15" />
            </BaseButton>
          </div>
        </article>

        <BaseSurface v-if="!connections.length" class="connection-list__empty" padding="large">
          还没有连接。先在左侧选择一个已发现的适配器类型创建。
        </BaseSurface>
      </section>
    </div>

    <BaseNotice v-if="!canList" tone="warning">
      当前账号没有 `connection:ws:list` 权限，无法查看连接列表和连接类型。
    </BaseNotice>

    <BaseSurface v-if="lastCreated" class="connection-result" padding="medium">
      <div class="connection-result__head">
        <div>
          <p class="connections-page__eyebrow">Created</p>
          <h2>{{ lastCreated.name }}</h2>
        </div>
        <span class="connection-state" :class="`connection-state--${statusTone(lastCreated)}`">
          {{ statusLabel(lastCreated) }}
        </span>
      </div>
      <p v-if="runtimeUnavailable(lastCreated)" class="connection-item__error">
        适配器监管器不可达，显示的是最近一次状态
      </p>
      <template v-if="lastCreated.endpointUrl">
        <p>传输接入地址（填到平台的 URL 字段）：</p>
        <div class="connection-result__copy">
          <code>{{ lastCreated.endpointUrl }}</code>
          <BaseButton size="small" appearance="secondary" @click="copy(lastCreated.endpointUrl)">
            <Copy :size="15" />
            复制
          </BaseButton>
        </div>
        <p class="connection-field__hint">
          URL 和协议凭据是两个独立字段，分别填入平台配置，不要拼接到同一个值里。
        </p>
      </template>
      <template v-if="createdFields.length">
        <p>协议配置（填到平台对应字段）：</p>
        <div v-for="field in createdFields" :key="field.name" class="connection-result__copy">
          <div>
            <span class="connection-field__label">{{ field.schema.title || field.name }}</span>
            <code>{{ field.value }}</code>
          </div>
          <BaseButton size="small" appearance="secondary" @click="copy(field.value)">
            <Copy :size="15" />
            复制
          </BaseButton>
        </div>
      </template>
      <p v-if="!lastCreated.endpointUrl && !createdFields.length">
        正向连接已交给适配器建立。若未上线，请检查配置与运行状态。
      </p>
    </BaseSurface>
  </div>
</template>

<style src="../styles/connections-page.css"></style>
