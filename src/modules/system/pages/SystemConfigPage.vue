<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { TriangleAlert } from '@lucide/vue'

import { ApiError } from '@shared/api/http-client'
import { hasPermission } from '@shared/session/permissions'
import { BaseButton, BaseNotice, BaseSurface, BaseSwitch, message } from '@shared/ui'

import { useSessionStore } from '../../auth/model/session-store'
import { systemApi } from '../api/system-api'
import type { SystemConfig, SystemConfigUpdate } from '../model/types'

/** 分组 key 的中文名。认不出的分组直接显示原始 key，而不是藏起来。 */
const GROUP_LABELS: Record<string, string> = {
  AUTH: '登录与注册',
  CONNECTION: '连接',
  DEPLOY: '部署',
  SYSTEM: '系统',
  WORKFLOW: '工作流',
}

/** 字段名的中文标签；没登记的按原样显示 JSON 里的键名。 */
const FIELD_LABELS: Record<string, string> = {
  days: '天数',
}

function fieldLabel(key: string): string {
  return FIELD_LABELS[key] ?? key
}

/** JSON 数字语法（不含前导零），用来在本地先挡一道，避免把 JSON 不认的数字发出去。 */
const JSON_NUMBER = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/

/**
 * 一条配置在界面上被拆成若干「字段」。
 *
 * <p>值统一是 JSON 对象，但每个键的意思**由这条配置自己决定** —— 后端只告诉我们开关键叫什么，
 * 其余字段它不认识，也不该认识。所以这里把值整体解析出来，按 JSON 的实际类型逐个字段渲染：
 * 布尔给开关、数字给数字框、字符串给文本框、认不出的嵌套结构给 JSON 编辑框。
 * 这样新加一条配置（比如 `{"enabled": true, "seconds": 30, "retries": 3}`）**前端不用改代码**。
 */
interface FieldValue {
  key: string
  kind: 'boolean' | 'number' | 'string' | 'json'
  /** 编辑用的自然文本：布尔是 'true'/'false'，数字是 '30'，字符串是去引号的内容。 */
  text: string
  /** 原始 JSON 值，用于渲染只读编辑器。 */
  raw: unknown
}

interface ConfigRow {
  config: SystemConfig
  /** 解析后的字段列表；值不是对象或解析失败时为空。 */
  fields: FieldValue[]
  /** 本地校验结果。非空时不给提交，并在这一行上显示原因。 */
  error: string
  /** 这一行当前对应的 JSON 文本（规范化后），用于判断有没有改过。 */
  json: string
  /** 整行是不是「关着的」—— 有开关且开关为 false。纯参数配置恒为 false。 */
  off: boolean
}

const session = useSessionStore()
const configs = ref<SystemConfig[]>([])
/** 草稿：configKey → 该行的 JSON 对象。改哪个字段就地改这个对象。 */
const draft = ref<Record<string, Record<string, unknown>>>({})
const isLoading = ref(true)
const isRefreshing = ref(false)
const isSaving = ref(false)
const loadFailed = ref(false)

const canList = computed(() => hasPermission(session.user?.permissions, 'system:config:list'))
const canUpdate = computed(() => hasPermission(session.user?.permissions, 'system:config:update'))

/** 把一个 JSON 值转成「好编辑的文本」。 */
function textOf(value: unknown): string {
  if (typeof value === 'string') return value
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (typeof value === 'number') return String(value)
  return JSON.stringify(value)
}

/** 从原始 JSON 值推断这一格该用什么控件。 */
function kindOf(value: unknown): FieldValue['kind'] {
  if (typeof value === 'boolean') return 'boolean'
  if (typeof value === 'number') return 'number'
  if (typeof value === 'string') return 'string'
  return 'json'
}

function parseObject(text: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(text)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null
  } catch {
    return null
  }
}

/** 当前这一行生效的值：有草稿用草稿，否则解析已保存的 JSON。 */
function valueOf(config: SystemConfig): Record<string, unknown> | null {
  return draft.value[config.configKey] ?? parseObject(config.configValue)
}

const rows = computed<ConfigRow[]>(() =>
  configs.value.map((config) => {
    const value = valueOf(config)
    const fields: FieldValue[] = value
      ? Object.entries(value).map(([key, raw]) => ({
          key,
          kind: kindOf(raw),
          text: textOf(raw),
          raw,
        }))
      : []
    const json = value ? JSON.stringify(value) : config.configValue.trim()
    const switchValue = config.switchKey ? value?.[config.switchKey] : undefined
    return {
      config,
      fields,
      error: value ? '' : '值不是合法的 JSON 对象，请先修好',
      json,
      off: typeof switchValue === 'boolean' && !switchValue,
    }
  }),
)

/** 按 config_group 分组。组内保持后端给的顺序（分组 + key 升序）。 */
const groups = computed(() => {
  const byGroup = new Map<string, ConfigRow[]>()
  rows.value.forEach((row) => {
    const list = byGroup.get(row.config.configGroup)
    if (list) list.push(row)
    else byGroup.set(row.config.configGroup, [row])
  })
  return [...byGroup.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, items]) => ({ key, label: GROUP_LABELS[key] ?? key, items }))
})

/**
 * 两边都规范化成紧凑 JSON 再比。
 *
 * <p>直接比字符串会把「库里有空格、前端序列化没空格」当成改动：种子数据是手写的
 * `{"enabled": true}`，而 `JSON.stringify` 产出 `{"enabled":true}`，于是一进页面所有配置
 * 都标成「未保存」。比较的是值，不是文本排版。
 */
function normalize(text: string): string {
  const parsed = parseObject(text)
  return parsed ? JSON.stringify(parsed) : text.trim()
}

function isChanged(row: ConfigRow): boolean {
  return row.json !== normalize(row.config.configValue)
}

const changedRows = computed(() => rows.value.filter(isChanged))
const invalidRows = computed(() => rows.value.filter((row) => row.error !== ''))
const canSave = computed(
  () => canUpdate.value && changedRows.value.length > 0 && invalidRows.value.length === 0,
)

/**
 * 拿到（必要时创建）某条配置的可编辑副本。
 *
 * <p>改动一律走「解析成对象 → 改一个键 → 重新序列化」，而不是在文本上做替换：
 * 这样下标跳变、引号转义这类文本操作的坑全都不存在，代价只是每次输入重新序列化一遍。
 */
function ensureDraft(config: SystemConfig): Record<string, unknown> | null {
  const existing = draft.value[config.configKey]
  if (existing) return existing
  const parsed = parseObject(config.configValue)
  if (!parsed) return null
  draft.value[config.configKey] = parsed
  return parsed
}

function setField(config: SystemConfig, key: string, kind: FieldValue['kind'], text: string): void {
  const target = ensureDraft(config)
  if (!target) return
  if (kind === 'boolean') {
    target[key] = text === 'true'
  } else if (kind === 'number') {
    // 数字框里可能是半截输入（"-"、""），先原样存文本，让下面的校验去拦
    target[key] = JSON_NUMBER.test(text.trim()) ? Number(text) : text.trim()
  } else if (kind === 'string') {
    target[key] = text
  } else {
    try {
      target[key] = JSON.parse(text)
    } catch {
      target[key] = text
    }
  }
}

/** 放弃修改：把草稿整个丢掉，界面上就回到 configs 的状态 */
function discard(): void {
  draft.value = {}
}

function accept(next: SystemConfig[]): void {
  next.forEach((item) => {
    const index = configs.value.findIndex((existing) => existing.configKey === item.configKey)
    if (index >= 0) configs.value[index] = item
  })
}

async function loadConfigs(): Promise<void> {
  isRefreshing.value = true
  try {
    configs.value = await systemApi.listConfigs()
    draft.value = {}
    loadFailed.value = false
  } catch (error) {
    loadFailed.value = true
    message.error(error instanceof ApiError ? error.message : '系统配置加载失败，请稍后重试')
  } finally {
    isLoading.value = false
    isRefreshing.value = false
  }
}

async function save(): Promise<void> {
  if (!canSave.value) return
  const updates: SystemConfigUpdate[] = changedRows.value.map((row) => ({
    key: row.config.configKey,
    value: row.json,
  }))
  isSaving.value = true
  try {
    accept(await systemApi.updateConfigs(updates))
    draft.value = {}
    message.success(`已保存 ${updates.length} 项系统配置`)
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '系统配置保存失败，请稍后重试')
  } finally {
    isSaving.value = false
  }
}

onMounted(loadConfigs)
</script>

<template>
  <main class="system-page config-page">
    <div class="system-page__shell">
      <header class="system-page__header">
        <div>
          <span class="system-page__eyebrow">System / Config</span>
          <h1>系统配置</h1>
          <p>
            一条配置就是一个值，值的形状由这条配置自己决定 —— 带开关的显示开关，带参数的显示输入框。
            改完点「保存」一起生效。
          </p>
        </div>
        <div class="system-page__actions">
          <BaseButton
            appearance="secondary"
            size="small"
            :loading="isRefreshing"
            @click="loadConfigs"
          >
            刷新
          </BaseButton>
        </div>
      </header>

      <BaseNotice v-if="!canList" tone="warning" title="没有权限">
        当前用户没有查看系统配置的权限。
      </BaseNotice>

      <BaseSurface v-else-if="isLoading" padding="large">
        <p class="system-page__empty">正在读取系统配置…</p>
      </BaseSurface>

      <BaseSurface v-else-if="loadFailed" padding="large">
        <p class="system-page__empty">系统配置没能加载出来。</p>
        <div class="config-page__retry">
          <BaseButton size="small" :loading="isRefreshing" @click="loadConfigs">重试</BaseButton>
        </div>
      </BaseSurface>

      <template v-else>
        <BaseNotice v-if="!canUpdate" tone="info" title="只读">
          当前用户只能查看系统配置。修改需要 <code>system:config:update</code> 权限。
        </BaseNotice>

        <BaseSurface v-if="!rows.length" padding="large">
          <p class="system-page__empty">还没有任何系统配置。</p>
        </BaseSurface>

        <section v-for="group in groups" :key="group.key" class="config-group">
          <header class="config-group__head">
            <div>
              <span class="config-group__kicker">{{ group.key }}</span>
              <h2>{{ group.label }}</h2>
            </div>
            <span class="system-page__count">{{ group.items.length }} 项</span>
          </header>

          <BaseSurface padding="none">
            <ul class="config-list">
              <li
                v-for="row in group.items"
                :key="row.config.configKey"
                class="config-row"
                :class="{
                  'config-row--changed': isChanged(row),
                  'config-row--off': row.off,
                  'config-row--invalid': row.error !== '',
                }"
              >
                <div class="config-row__copy">
                  <div class="config-row__title">
                    <strong>{{ row.config.name }}</strong>
                    <span v-if="row.config.builtin" class="config-row__tag">内置</span>
                    <span v-if="row.config.locked" class="config-row__tag config-row__tag--locked">
                      不允许关闭
                    </span>
                    <span v-if="isChanged(row)" class="config-row__tag config-row__tag--changed">
                      未保存
                    </span>
                  </div>
                  <p v-if="row.config.description" class="config-row__desc">
                    {{ row.config.description }}
                  </p>
                  <code class="config-row__key">{{ row.config.configKey }}</code>
                </div>

                <!--
                  每个字段按 JSON 的实际类型渲染，开关排在第一位、参数跟在后面。
                  没有 switchKey 的配置（纯参数）这一格自然就只有一个输入框。
                -->
                <div class="config-row__fields">
                  <div
                    v-for="field in row.fields"
                    :key="field.key"
                    class="config-field"
                    :class="{ 'config-field--switch': field.key === row.config.switchKey }"
                  >
                    <template v-if="field.kind === 'boolean'">
                      <BaseSwitch
                        :model-value="field.text === 'true'"
                        :disabled="
                          !canUpdate || (row.config.locked && field.key === row.config.switchKey)
                        "
                        :aria-label="`${row.config.name}：${field.text === 'true' ? '已开启' : '已关闭'}`"
                        @update:model-value="
                          (next) =>
                            setField(row.config, field.key, 'boolean', next ? 'true' : 'false')
                        "
                      />
                      <span class="config-field__label">
                        {{ field.text === 'true' ? '已开启' : '已关闭' }}
                      </span>
                    </template>

                    <template v-else-if="field.kind === 'number'">
                      <span class="config-field__label">{{ fieldLabel(field.key) }}</span>
                      <input
                        class="config-field__input config-field__input--number"
                        type="text"
                        inputmode="numeric"
                        :value="field.text"
                        :disabled="!canUpdate || row.config.locked"
                        :aria-label="`${row.config.name} · ${field.key}`"
                        @input="
                          setField(
                            row.config,
                            field.key,
                            'number',
                            ($event.target as HTMLInputElement).value,
                          )
                        "
                      />
                    </template>

                    <template v-else-if="field.kind === 'string'">
                      <span class="config-field__label">{{ fieldLabel(field.key) }}</span>
                      <input
                        class="config-field__input"
                        type="text"
                        :value="field.text"
                        :disabled="!canUpdate || row.config.locked"
                        :aria-label="`${row.config.name} · ${field.key}`"
                        @input="
                          setField(
                            row.config,
                            field.key,
                            'string',
                            ($event.target as HTMLInputElement).value,
                          )
                        "
                      />
                    </template>

                    <!-- 认不出的嵌套结构：给一个 JSON 编辑框，而不是假装能编辑 -->
                    <template v-else>
                      <span class="config-field__label">{{ fieldLabel(field.key) }}</span>
                      <textarea
                        class="config-field__json"
                        rows="3"
                        spellcheck="false"
                        :value="JSON.stringify(field.raw, null, 2)"
                        :disabled="!canUpdate || row.config.locked"
                        :aria-label="`${row.config.name} · ${field.key}`"
                        @input="
                          setField(
                            row.config,
                            field.key,
                            'json',
                            ($event.target as HTMLTextAreaElement).value,
                          )
                        "
                      />
                    </template>
                  </div>
                </div>

                <p v-if="row.error" class="config-row__note config-row__note--error" role="alert">
                  {{ row.error }}
                </p>

                <p v-else-if="row.config.locked" class="config-row__note config-row__note--locked">
                  <TriangleAlert :size="14" aria-hidden="true" />
                  {{ row.config.lockedReason }}
                </p>

                <p v-else-if="row.off" class="config-row__note">
                  这项已关闭 —— 参数还留着，重新打开后按它执行。
                </p>
              </li>
            </ul>
          </BaseSurface>
        </section>

        <div v-if="rows.length" class="config-savebar">
          <div class="config-savebar__copy">
            <strong v-if="changedRows.length">已修改 {{ changedRows.length }} 项，还没保存</strong>
            <strong v-else>没有未保存的修改</strong>
            <span v-if="invalidRows.length" class="config-savebar__alert">
              <TriangleAlert :size="14" aria-hidden="true" />
              有 {{ invalidRows.length }} 项格式不对，先改好才能保存
            </span>
            <span v-else>保存后立即生效。</span>
          </div>
          <div class="system-page__actions">
            <BaseButton
              appearance="ghost"
              size="small"
              :disabled="!changedRows.length || isSaving"
              @click="discard"
            >
              放弃修改
            </BaseButton>
            <BaseButton size="small" :disabled="!canSave" :loading="isSaving" @click="save">
              保存
            </BaseButton>
          </div>
        </div>
      </template>
    </div>
  </main>
</template>

<style scoped>
.config-group + .config-group {
  margin-block-start: var(--sys-space-6);
}

.config-group__head {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--sys-space-4);
  margin-block-end: var(--sys-space-3);
}

.config-group__kicker {
  color: var(--sys-color-text-accent);
  font: var(--sys-typography-label);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.config-group__head h2 {
  margin: var(--sys-space-1) 0 0;
  font: 700 1.2rem/1.2 var(--ref-font-sans);
}

.config-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.config-row {
  display: grid;
  /*
   * 说明 / 字段控件，只用两列 —— 开关和参数同属「这一条的值」，放在同一格里按顺序排，
   * 这样以后加一个参数字段不需要动布局。
   *
   * minmax(0, 1fr) 而不是 1fr：input 的固有最小尺寸是 min-content（约 size=20 个字符），
   * 1fr 的下限是 auto，会被它顶开；见决策记录 D70。
   */
  grid-template-columns: minmax(0, 1fr) minmax(14rem, 24rem);
  align-items: center;
  gap: var(--sys-space-4);
  padding: var(--sys-space-4) var(--sys-space-5);
  border-block-end: 1px solid var(--sys-color-border);
}

.config-list > .config-row:last-child {
  border-block-end: 0;
}

.config-row--changed {
  background: var(--sys-color-info-subtle);
}

.config-row--off .config-row__copy {
  opacity: 0.62;
}

.config-row--invalid {
  background: var(--sys-color-danger-subtle);
}

.config-row__copy {
  min-inline-size: 0;
}

.config-row__title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sys-space-2);
}

.config-row__title strong {
  font: var(--sys-typography-label-strong);
  font-size: 0.9375rem;
}

.config-row__tag {
  border-radius: var(--ref-radius-round);
  padding-inline: var(--sys-space-2);
  background: var(--sys-color-surface-muted);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  font-size: 0.75rem;
}

.config-row__tag--changed {
  background: var(--sys-color-info-border);
  color: var(--sys-color-info-text);
}

.config-row__tag--locked {
  background: var(--sys-color-warning-subtle);
  color: var(--sys-color-warning-text);
}

.config-row__desc {
  margin: var(--sys-space-1) 0 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.config-row__key {
  display: inline-block;
  margin-block-start: var(--sys-space-2);
  color: var(--sys-color-text-accent);
  font-family: var(--ref-font-mono);
  font-size: 0.75rem;
}

.config-row__fields {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sys-space-3);
  min-inline-size: 0;
}

.config-field {
  display: flex;
  align-items: center;
  gap: var(--sys-space-2);
  min-inline-size: 0;
}

/* 开关是固定自然宽度不参与拉伸；数字和字符串字段撑满剩余空间 */
.config-field:not(.config-field--switch) {
  flex: 1 1 auto;
}

.config-field__label {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  white-space: nowrap;
}

.config-field__input {
  flex: 1 1 auto;
  inline-size: 100%;
  min-inline-size: 0;
  min-block-size: 2.5rem;
  border: var(--cmp-field-border-width) solid var(--sys-color-border-strong);
  border-radius: var(--cmp-field-radius);
  padding-inline: var(--sys-space-3);
  background: var(--sys-color-field);
  color: var(--sys-color-text);
  font: var(--sys-typography-body-compact);
  outline: 0;
}

.config-field__input--number {
  text-align: right;
}

.config-field__input:focus,
.config-field__json:focus {
  border-color: var(--sys-color-action-primary);
  box-shadow: 0 0 0 var(--sys-focus-width) var(--sys-color-focus);
}

.config-field__input:disabled,
.config-field__json:disabled {
  cursor: not-allowed;
  opacity: var(--sys-opacity-disabled);
}

.config-field__json {
  flex: 1 1 auto;
  inline-size: 100%;
  min-inline-size: 0;
  border: var(--cmp-field-border-width) solid var(--sys-color-border-strong);
  border-radius: var(--cmp-field-radius);
  padding: var(--sys-space-3);
  background: var(--sys-color-field);
  color: var(--sys-color-text);
  font-family: var(--ref-font-mono);
  font-size: 0.8125rem;
  line-height: 1.5;
  resize: vertical;
  outline: 0;
}

.config-row__note {
  display: flex;
  align-items: start;
  grid-column: 1 / -1;
  gap: var(--sys-space-2);
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.config-row__note--error {
  color: var(--sys-color-danger-text);
}

.config-row__note--locked {
  color: var(--sys-color-warning-text);
}

.config-savebar {
  position: sticky;
  inset-block-end: var(--sys-space-4);
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--sys-space-4);
  margin-block-start: var(--sys-space-6);
  border: 1px solid var(--sys-color-border-strong);
  border-radius: var(--cmp-surface-radius);
  padding: var(--sys-space-4) var(--sys-space-5);
  background: var(--sys-color-surface-raised);
  box-shadow: var(--sys-shadow-raised);
}

.config-savebar__copy {
  display: grid;
  gap: var(--sys-space-1);
  min-inline-size: 0;
}

.config-savebar__copy span {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.config-savebar__alert {
  display: inline-flex;
  align-items: center;
  gap: var(--sys-space-2);
  color: var(--sys-color-danger-text) !important;
}

.config-page__retry {
  display: flex;
  justify-content: center;
}

@media (max-width: 60rem) {
  .config-row {
    grid-template-columns: minmax(0, 1fr);
    row-gap: var(--sys-space-3);
  }

  .config-row__fields {
    justify-content: flex-start;
  }
}
</style>
