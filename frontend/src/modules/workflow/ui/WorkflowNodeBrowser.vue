<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Boxes, Cable, ChevronLeft, Plus, Search, X } from '@lucide/vue'

import type { ConnectionRecord } from '@modules/connections'
import { BaseButton } from '@shared/ui'

import type { WorkflowNodeCatalog, WorkflowNodeCatalogItem } from '../api/workflow-api'
import {
  collectCatalogItems,
  groupCatalogItems,
  matchesCatalogQuery,
  type CatalogGroup,
  type CatalogScope,
} from '../model/catalog'
import {
  catalogIdentity,
  catalogLabel,
  catalogNodeKind,
  catalogNodeTypeLabel,
  nodeAccent,
  sortParameters,
} from '../model/graph'

type NodeTypeFilter = 'all' | WorkflowNodeCatalogItem['nodeType']

const ALL_PLUGINS = '__all__'
const PLUGIN_PAGE_SIZE = 40
const NODE_PAGE_SIZE = 60

type MobileView = 'plugins' | 'nodes' | 'detail'

const props = defineProps<{
  catalog: WorkflowNodeCatalog
  connections: ConnectionRecord[]
  lockedAdapterPluginVersionId?: string | null
}>()

const emit = defineEmits<{
  close: []
  add: [item: WorkflowNodeCatalogItem]
}>()

const scope = ref<CatalogScope>('all')
const typeFilter = ref<NodeTypeFilter>('all')
const keyword = ref('')
const selectedPluginKey = ref<string>(ALL_PLUGINS)
const selectedNodeId = ref<string | null>(null)
const visibleGroupCount = ref(PLUGIN_PAGE_SIZE)
const visibleNodeCount = ref(NODE_PAGE_SIZE)
const mobileQuery = window.matchMedia('(max-width: 56rem)')
const isMobile = ref(mobileQuery.matches)
const mobileView = ref<MobileView>('plugins')
const searchRef = ref<HTMLInputElement | null>(null)
const nodeListRef = ref<HTMLElement | null>(null)

const scopeCache = computed(() => ({
  all: collectCatalogItems(
    props.catalog,
    'all',
    props.connections,
    props.lockedAdapterPluginVersionId,
  ),
  public: collectCatalogItems(
    props.catalog,
    'public',
    props.connections,
    props.lockedAdapterPluginVersionId,
  ),
  adapter: collectCatalogItems(
    props.catalog,
    'adapter',
    props.connections,
    props.lockedAdapterPluginVersionId,
  ),
}))

const scopeOptions = computed<Array<{ value: CatalogScope; label: string; count: number }>>(() => [
  { value: 'all', label: '全部', count: scopeCache.value.all.length },
  { value: 'public', label: '公共', count: scopeCache.value.public.length },
  { value: 'adapter', label: '适配器', count: scopeCache.value.adapter.length },
])

const scopeItems = computed(() => scopeCache.value[scope.value])
const keywordItems = computed(() =>
  scopeItems.value.filter((item) => matchesCatalogQuery(item, keyword.value)),
)

const typeOptions = computed<Array<{ value: NodeTypeFilter; label: string; count: number }>>(() => [
  {
    value: 'all',
    label: '全部',
    count: keywordItems.value.length,
  },
  {
    value: 'EVENT',
    label: '事件',
    count: keywordItems.value.filter((item) => item.nodeType === 'EVENT').length,
  },
  {
    value: 'ACTION',
    label: '动作',
    count: keywordItems.value.filter((item) => item.nodeType === 'ACTION').length,
  },
  {
    value: 'NODE',
    label: '节点',
    count: keywordItems.value.filter((item) => item.nodeType === 'NODE').length,
  },
])

const filteredItems = computed(() =>
  typeFilter.value === 'all'
    ? keywordItems.value
    : keywordItems.value.filter((item) => item.nodeType === typeFilter.value),
)

const groups = computed<CatalogGroup[]>(() => groupCatalogItems(filteredItems.value))
const visibleGroups = computed(() => groups.value.slice(0, visibleGroupCount.value))
const hasMoreGroups = computed(() => visibleGroupCount.value < groups.value.length)

const selectedGroup = computed(
  () => groups.value.find((group) => group.key === selectedPluginKey.value) ?? null,
)
const listItems = computed(() =>
  selectedPluginKey.value === ALL_PLUGINS
    ? filteredItems.value
    : (selectedGroup.value?.items ?? []),
)
const visibleListItems = computed(() => listItems.value.slice(0, visibleNodeCount.value))
const hasMoreNodes = computed(() => visibleNodeCount.value < listItems.value.length)
const selectedItem = computed(
  () => listItems.value.find((item) => catalogIdentity(item) === selectedNodeId.value) ?? null,
)
const selectedPluginTitle = computed(() => selectedGroup.value?.title ?? '全部结果')
const parameters = computed(() => sortParameters(selectedItem.value?.parameters))
const returnFields = computed(() => selectedItem.value?.returnFields ?? [])

function groupVersion(group: CatalogGroup): string {
  return group.items.find((item) => item.pluginVersion)?.pluginVersion ?? ''
}

function selectPlugin(key: string): void {
  selectedPluginKey.value = key
  if (isMobile.value) mobileView.value = 'nodes'
}

function selectNode(item: WorkflowNodeCatalogItem): void {
  selectedNodeId.value = catalogIdentity(item)
  if (isMobile.value) mobileView.value = 'detail'
}

function moveSelection(delta: number): void {
  const items = listItems.value
  if (!items.length) return
  const current = items.findIndex((item) => catalogIdentity(item) === selectedNodeId.value)
  const next = Math.min(Math.max((current < 0 ? 0 : current) + delta, 0), items.length - 1)
  const item = items[next]
  if (!item) return
  selectedNodeId.value = catalogIdentity(item)
  if (next >= visibleNodeCount.value) {
    visibleNodeCount.value = Math.min(next + NODE_PAGE_SIZE, items.length)
  }
  if (isMobile.value) mobileView.value = 'nodes'
  void nextTick(scrollSelectedNode)
}

function scrollSelectedNode(): void {
  nodeListRef.value
    ?.querySelector<HTMLElement>('[data-selected="true"]')
    ?.scrollIntoView({ block: 'nearest' })
}

function handleSearchKeydown(event: KeyboardEvent): void {
  if (event.isComposing) return
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    moveSelection(event.key === 'ArrowDown' ? 1 : -1)
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    if (selectedItem.value) emit('add', selectedItem.value)
  }
}

function handlePluginScroll(event: Event): void {
  if (!hasMoreGroups.value) return
  const element = event.target as HTMLElement
  if (element.scrollHeight - element.scrollTop - element.clientHeight > 140) return
  visibleGroupCount.value = Math.min(
    visibleGroupCount.value + PLUGIN_PAGE_SIZE,
    groups.value.length,
  )
}

function handleNodeScroll(event: Event): void {
  if (!hasMoreNodes.value) return
  const element = event.target as HTMLElement
  if (element.scrollHeight - element.scrollTop - element.clientHeight > 160) return
  visibleNodeCount.value = Math.min(visibleNodeCount.value + NODE_PAGE_SIZE, listItems.value.length)
}

function handleWindowKeydown(event: KeyboardEvent): void {
  if (event.isComposing) return
  if (event.key === 'Escape') emit('close')
}

watch(groups, (nextGroups) => {
  if (
    selectedPluginKey.value !== ALL_PLUGINS &&
    !nextGroups.some((group) => group.key === selectedPluginKey.value)
  ) {
    selectedPluginKey.value = ALL_PLUGINS
  }
})

watch(
  listItems,
  (items) => {
    visibleNodeCount.value = NODE_PAGE_SIZE
    if (!items.some((item) => catalogIdentity(item) === selectedNodeId.value)) {
      const first = items[0]
      selectedNodeId.value = first ? catalogIdentity(first) : null
    }
  },
  { immediate: true },
)

watch([scope, typeFilter, keyword], () => {
  visibleGroupCount.value = PLUGIN_PAGE_SIZE
  selectedPluginKey.value = ALL_PLUGINS
  if (isMobile.value && keyword.value.trim()) mobileView.value = 'nodes'
})

let previousBodyOverflow = ''

function syncMobile(): void {
  const next = mobileQuery.matches
  if (next === isMobile.value) return
  isMobile.value = next
  if (next) mobileView.value = 'plugins'
}

onMounted(() => {
  previousBodyOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', handleWindowKeydown)
  mobileQuery.addEventListener('change', syncMobile)
  void nextTick(() => searchRef.value?.focus())
})

onBeforeUnmount(() => {
  document.body.style.overflow = previousBodyOverflow
  window.removeEventListener('keydown', handleWindowKeydown)
  mobileQuery.removeEventListener('change', syncMobile)
})
</script>

<template>
  <div class="workflow-node-browser" role="presentation" @wheel.stop @click.self="$emit('close')">
    <section
      class="workflow-node-browser__dialog"
      role="dialog"
      aria-modal="true"
      aria-label="节点浏览器"
    >
      <header class="workflow-node-browser__header">
        <div class="workflow-node-browser__brand">
          <Boxes :size="18" aria-hidden="true" />
          <div>
            <strong>节点浏览器</strong>
            <small>{{ filteredItems.length }} 个节点 · {{ groups.length }} 个插件</small>
          </div>
        </div>

        <label class="workflow-node-browser__search">
          <Search :size="16" aria-hidden="true" />
          <input
            ref="searchRef"
            v-model="keyword"
            type="search"
            placeholder="搜索节点名称、描述、参数、插件…"
            @keydown="handleSearchKeydown"
          />
        </label>

        <div class="workflow-node-browser__scopes" role="group" aria-label="节点范围">
          <button
            v-for="option in scopeOptions"
            :key="option.value"
            type="button"
            :class="{ 'is-active': scope === option.value }"
            @click="scope = option.value"
          >
            {{ option.label }}
            <small>{{ option.count }}</small>
          </button>
        </div>

        <button
          type="button"
          class="workflow-node-browser__close"
          aria-label="关闭节点浏览器"
          @click="$emit('close')"
        >
          <X :size="18" />
        </button>
      </header>

      <div class="workflow-node-browser__filters">
        <div class="workflow-node-browser__types" role="group" aria-label="节点类型">
          <button
            v-for="option in typeOptions"
            :key="option.value"
            type="button"
            :class="{ 'is-active': typeFilter === option.value }"
            @click="typeFilter = option.value"
          >
            {{ option.label }}
            <small>{{ option.count }}</small>
          </button>
        </div>
      </div>

      <div class="workflow-node-browser__body">
        <aside
          class="workflow-node-browser__panel workflow-node-browser__plugins"
          :class="{ 'is-mobile-hidden': isMobile && mobileView !== 'plugins' }"
        >
          <header class="workflow-node-browser__panel-head">
            <span>插件</span>
            <small>{{ groups.length }}</small>
          </header>
          <div class="workflow-node-browser__panel-scroll" @scroll.passive="handlePluginScroll">
            <button
              type="button"
              class="workflow-node-browser__plugin"
              :class="{ 'is-active': selectedPluginKey === ALL_PLUGINS }"
              @click="selectPlugin(ALL_PLUGINS)"
            >
              <span>全部结果</span>
              <small>{{ filteredItems.length }}</small>
            </button>
            <button
              v-for="group in visibleGroups"
              :key="group.key"
              type="button"
              class="workflow-node-browser__plugin"
              :class="{ 'is-active': selectedPluginKey === group.key }"
              @click="selectPlugin(group.key)"
            >
              <span>{{ group.title }}</span>
              <small v-if="groupVersion(group)">v{{ groupVersion(group) }}</small>
              <small>{{ group.items.length }}</small>
            </button>
          </div>
        </aside>

        <section
          class="workflow-node-browser__panel workflow-node-browser__nodes"
          :class="{ 'is-mobile-hidden': isMobile && mobileView !== 'nodes' }"
        >
          <header class="workflow-node-browser__panel-head">
            <button
              v-if="isMobile"
              type="button"
              class="workflow-node-browser__back"
              aria-label="返回插件列表"
              @click="mobileView = 'plugins'"
            >
              <ChevronLeft :size="16" />
            </button>
            <span>{{ selectedPluginTitle }}</span>
            <small>{{ listItems.length }}</small>
          </header>
          <div
            ref="nodeListRef"
            class="workflow-node-browser__panel-scroll"
            @scroll.passive="handleNodeScroll"
          >
            <p v-if="!listItems.length" class="workflow-node-browser__empty">没有匹配的节点</p>
            <div
              v-for="item in visibleListItems"
              :key="catalogIdentity(item)"
              class="workflow-node-browser__node-row"
            >
              <button
                type="button"
                class="workflow-node-browser__node"
                :class="{ 'is-active': catalogIdentity(item) === selectedNodeId }"
                :data-selected="catalogIdentity(item) === selectedNodeId"
                :style="{ '--node-accent': nodeAccent(catalogNodeKind(item)) }"
                :title="`单击查看，双击添加到画布：${catalogLabel(item)}`"
                @click="selectNode(item)"
                @dblclick="emit('add', item)"
              >
                <span class="workflow-node-browser__node-title">
                  <strong>{{ catalogLabel(item) }}</strong>
                  <em>{{ catalogNodeTypeLabel(item) }}</em>
                </span>
                <small class="workflow-node-browser__node-desc">
                  {{ item.description || item.nodeKey }}
                </small>
                <span
                  v-if="selectedPluginKey === ALL_PLUGINS"
                  class="workflow-node-browser__node-source"
                >
                  {{ item.pluginKey || '系统节点' }}
                </span>
              </button>
              <button
                type="button"
                class="workflow-node-browser__node-add"
                :title="`添加到画布：${catalogLabel(item)}`"
                :aria-label="`添加到画布：${catalogLabel(item)}`"
                @click="emit('add', item)"
              >
                <Plus :size="15" />
              </button>
            </div>
          </div>
        </section>

        <aside
          class="workflow-node-browser__panel workflow-node-browser__detail"
          :class="{ 'is-mobile-hidden': isMobile && mobileView !== 'detail' }"
        >
          <template v-if="selectedItem">
            <header class="workflow-node-browser__detail-head">
              <button
                v-if="isMobile"
                type="button"
                class="workflow-node-browser__back"
                aria-label="返回节点列表"
                @click="mobileView = 'nodes'"
              >
                <ChevronLeft :size="16" />
              </button>
              <div>
                <strong>{{ catalogLabel(selectedItem) }}</strong>
                <small>
                  {{ selectedItem.pluginKey || '系统节点' }}
                  <template v-if="selectedItem.pluginVersion">
                    · v{{ selectedItem.pluginVersion }}
                  </template>
                  · {{ selectedItem.nodeKey }}
                </small>
              </div>
              <span
                class="workflow-node-browser__kind"
                :style="{ '--node-accent': nodeAccent(catalogNodeKind(selectedItem)) }"
              >
                {{ catalogNodeTypeLabel(selectedItem) }}
              </span>
            </header>

            <div class="workflow-node-browser__detail-scroll">
              <p v-if="selectedItem.description" class="workflow-node-browser__description">
                {{ selectedItem.description }}
              </p>

              <div class="workflow-node-browser__facts">
                <span v-if="selectedItem.connectionType">
                  <Cable :size="13" aria-hidden="true" />
                  {{ selectedItem.connectionType }}
                </span>
                <span v-if="selectedItem.sourceRef">{{ selectedItem.sourceRef }}</span>
              </div>

              <section v-if="parameters.length" class="workflow-node-browser__section">
                <h3>参数</h3>
                <ul class="workflow-node-browser__params">
                  <li v-for="parameter in parameters" :key="parameter.name">
                    <span class="workflow-node-browser__param-title">
                      {{ parameter.displayName || parameter.name }}
                      <em v-if="parameter.required">必填</em>
                    </span>
                    <small>{{ parameter.type || 'object' }} · {{ parameter.name }}</small>
                    <p v-if="parameter.description">{{ parameter.description }}</p>
                  </li>
                </ul>
              </section>

              <section v-if="returnFields.length" class="workflow-node-browser__section">
                <h3>返回</h3>
                <ul class="workflow-node-browser__returns">
                  <li v-for="field in returnFields" :key="field.path || field.name">
                    <span>{{ field.displayName || field.name || field.path }}</span>
                    <small>
                      {{ field.type || 'object' }}
                      <template v-if="field.path">· {{ field.path }}</template>
                    </small>
                  </li>
                </ul>
              </section>
            </div>

            <footer class="workflow-node-browser__detail-foot">
              <BaseButton size="small" @click="emit('add', selectedItem)">
                <Plus :size="14" />
                添加到画布
              </BaseButton>
            </footer>
          </template>
          <p v-else class="workflow-node-browser__empty">选择一个节点查看详情</p>
        </aside>
      </div>
    </section>
  </div>
</template>

<style scoped>
.workflow-node-browser {
  position: fixed;
  z-index: 1100;
  inset: 0;
  display: grid;
  place-items: center;
  background: color-mix(in srgb, var(--sys-color-text) 34%, transparent);
  padding: var(--sys-space-4);
}

.workflow-node-browser__dialog {
  display: flex;
  inline-size: min(96rem, calc(100vw - 2rem));
  block-size: min(54rem, calc(100dvh - 2rem));
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.85rem;
  background: var(--sys-color-surface-raised);
  box-shadow: var(--sys-shadow-raised);
}

.workflow-node-browser__header {
  display: flex;
  align-items: center;
  border-block-end: 1px solid var(--sys-color-border);
  gap: var(--sys-space-3);
  padding: var(--sys-space-3) var(--sys-space-4);
}

.workflow-node-browser__brand {
  display: flex;
  min-inline-size: 12rem;
  align-items: center;
  color: var(--sys-color-action-primary);
  gap: var(--sys-space-2);
}

.workflow-node-browser__brand strong,
.workflow-node-browser__brand small {
  display: block;
}

.workflow-node-browser__brand strong {
  color: var(--sys-color-text);
  font: var(--sys-typography-label-strong);
}

.workflow-node-browser__brand small {
  margin-block-start: 0.15rem;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-node-browser__search {
  display: flex;
  min-inline-size: 0;
  flex: 1;
  align-items: center;
  border: 1px solid var(--sys-color-border-strong);
  border-radius: 0.65rem;
  background: var(--sys-color-field);
  color: var(--sys-color-text-muted);
  gap: var(--sys-space-2);
  padding: 0 var(--sys-space-3);
}

.workflow-node-browser__search:focus-within {
  border-color: var(--sys-color-action-primary);
  box-shadow: 0 0 0 var(--sys-focus-width) var(--sys-color-focus);
}

.workflow-node-browser__search input {
  min-inline-size: 0;
  flex: 1;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--sys-color-text);
  font: var(--sys-typography-body-compact);
  padding-block: 0.55rem;
}

.workflow-node-browser__scopes,
.workflow-node-browser__types {
  display: flex;
  align-items: center;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.65rem;
  background: var(--sys-color-surface-muted);
  gap: 0.15rem;
  padding: 0.15rem;
}

.workflow-node-browser__scopes button,
.workflow-node-browser__types button {
  display: inline-flex;
  align-items: center;
  border: 0;
  border-radius: 0.5rem;
  background: transparent;
  color: var(--sys-color-text-muted);
  cursor: pointer;
  font: var(--sys-typography-label);
  gap: 0.35rem;
  padding: 0.4rem 0.6rem;
}

.workflow-node-browser__scopes button:hover,
.workflow-node-browser__types button:hover {
  color: var(--sys-color-text);
}

.workflow-node-browser__scopes button.is-active,
.workflow-node-browser__types button.is-active {
  background: var(--sys-color-surface-raised);
  box-shadow: var(--sys-shadow-raised);
  color: var(--sys-color-text);
}

.workflow-node-browser__scopes small,
.workflow-node-browser__types small {
  color: var(--sys-color-text-subtle);
  font: var(--sys-typography-caption);
}

.workflow-node-browser__close {
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

.workflow-node-browser__close:hover {
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-text);
}

.workflow-node-browser__filters {
  display: flex;
  align-items: center;
  border-block-end: 1px solid var(--sys-color-border);
  padding: var(--sys-space-2) var(--sys-space-4);
}

.workflow-node-browser__body {
  display: grid;
  min-block-size: 0;
  min-inline-size: 0;
  flex: 1;
  overflow: hidden;
  grid-template-columns: minmax(14rem, 17rem) minmax(18rem, 1fr) minmax(20rem, 25rem);
  grid-template-rows: minmax(0, 1fr);
}

.workflow-node-browser__panel {
  display: flex;
  min-block-size: 0;
  min-inline-size: 0;
  flex-direction: column;
}

.workflow-node-browser__panel + .workflow-node-browser__panel {
  border-inline-start: 1px solid var(--sys-color-border);
}

.workflow-node-browser__panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-block-end: 1px solid var(--sys-color-border);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-label-strong);
  gap: var(--sys-space-2);
  padding: 0.6rem var(--sys-space-3);
}

.workflow-node-browser__panel-head small {
  color: var(--sys-color-text-subtle);
  font: var(--sys-typography-caption);
}

.workflow-node-browser__panel-head > span {
  min-inline-size: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-node-browser__back {
  display: grid;
  inline-size: 1.75rem;
  block-size: 1.75rem;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.5rem;
  background: var(--sys-color-field);
  color: var(--sys-color-text-muted);
  cursor: pointer;
}

.workflow-node-browser__back:hover {
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-text);
}

.workflow-node-browser__panel-scroll {
  min-block-size: 0;
  min-inline-size: 0;
  flex: 1;
  overflow-x: hidden;
  overflow-y: auto;
  padding: var(--sys-space-2);
}

.workflow-node-browser__plugin {
  display: flex;
  inline-size: 100%;
  align-items: center;
  border: 0;
  border-radius: 0.55rem;
  background: transparent;
  color: var(--sys-color-text);
  cursor: pointer;
  font: var(--sys-typography-body-compact);
  gap: var(--sys-space-2);
  padding: 0.5rem 0.6rem;
  text-align: start;
}

.workflow-node-browser__plugin:hover {
  background: var(--sys-color-action-subtle-hover);
}

.workflow-node-browser__plugin.is-active {
  background: color-mix(in srgb, var(--sys-color-action-primary) 12%, transparent);
  color: var(--sys-color-action-primary);
}

.workflow-node-browser__plugin span {
  min-inline-size: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-node-browser__plugin small {
  flex: 0 0 auto;
  color: var(--sys-color-text-subtle);
  font: var(--sys-typography-caption);
}

.workflow-node-browser__nodes .workflow-node-browser__panel-scroll {
  display: grid;
  align-content: start;
  gap: var(--sys-space-2);
}

.workflow-node-browser__node-row {
  display: flex;
  align-items: stretch;
  gap: 0.35rem;
}

.workflow-node-browser__node {
  display: grid;
  min-inline-size: 0;
  flex: 1;
  overflow: hidden;
  border: 1px solid var(--sys-color-border);
  border-inline-start: 0.22rem solid var(--node-accent, var(--sys-color-action-primary));
  border-radius: 0.65rem;
  background: var(--sys-color-surface-raised);
  color: var(--sys-color-text);
  cursor: pointer;
  gap: 0.25rem;
  padding: var(--sys-space-3);
  text-align: start;
}

.workflow-node-browser__node-add {
  display: grid;
  inline-size: 2.35rem;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.65rem;
  background: var(--sys-color-field);
  color: var(--sys-color-text-muted);
  cursor: pointer;
}

.workflow-node-browser__node-add:hover {
  border-color: color-mix(in srgb, var(--sys-color-action-primary) 42%, var(--sys-color-border));
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-action-primary);
}

.workflow-node-browser__node:hover {
  border-color: color-mix(
    in srgb,
    var(--node-accent, var(--sys-color-action-primary)) 42%,
    var(--sys-color-border)
  );
}

.workflow-node-browser__node.is-active {
  background: color-mix(
    in srgb,
    var(--node-accent, var(--sys-color-action-primary)) 8%,
    var(--sys-color-surface-raised)
  );
  box-shadow: 0 0 0 1px
    color-mix(in srgb, var(--node-accent, var(--sys-color-action-primary)) 50%, transparent);
}

.workflow-node-browser__node-title {
  display: flex;
  min-inline-size: 0;
  align-items: center;
  justify-content: space-between;
  gap: var(--sys-space-2);
}

.workflow-node-browser__node-title strong {
  min-inline-size: 0;
  flex: 1;
  overflow: hidden;
  font: var(--sys-typography-body-compact);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-node-browser__node-title em {
  flex: 0 0 auto;
  border-radius: 999px;
  background: color-mix(
    in srgb,
    var(--node-accent, var(--sys-color-action-primary)) 16%,
    transparent
  );
  color: var(--node-accent, var(--sys-color-action-primary));
  font: var(--sys-typography-label);
  font-style: normal;
  padding: 0.12rem 0.42rem;
}

.workflow-node-browser__node-desc {
  min-inline-size: 0;
  overflow: hidden;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-node-browser__node-source {
  display: block;
  min-inline-size: 0;
  overflow: hidden;
  color: var(--sys-color-action-primary);
  font: var(--sys-typography-caption);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-node-browser__detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  border-block-end: 1px solid var(--sys-color-border);
  gap: var(--sys-space-3);
  padding: var(--sys-space-4);
}

.workflow-node-browser__detail-head > div {
  min-inline-size: 0;
  flex: 1;
}

.workflow-node-browser__detail-head strong,
.workflow-node-browser__detail-head small {
  display: block;
}

.workflow-node-browser__detail-head strong {
  font: var(--sys-typography-label-strong);
}

.workflow-node-browser__detail-head small {
  margin-block-start: 0.25rem;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  overflow-wrap: anywhere;
}

.workflow-node-browser__kind {
  flex: 0 0 auto;
  border-radius: 999px;
  background: color-mix(
    in srgb,
    var(--node-accent, var(--sys-color-action-primary)) 16%,
    transparent
  );
  color: var(--node-accent, var(--sys-color-action-primary));
  font: var(--sys-typography-label);
  padding: 0.15rem 0.5rem;
}

.workflow-node-browser__detail-scroll {
  min-block-size: 0;
  flex: 1;
  overflow-y: auto;
  padding: var(--sys-space-4);
}

.workflow-node-browser__description {
  margin: 0;
  color: var(--sys-color-text);
  font: var(--sys-typography-body-compact);
  line-height: 1.5;
}

.workflow-node-browser__facts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sys-space-2);
  margin-block-start: var(--sys-space-3);
}

.workflow-node-browser__facts span {
  display: inline-flex;
  align-items: center;
  border-radius: 0.45rem;
  background: var(--sys-color-surface-muted);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  gap: 0.3rem;
  padding: 0.2rem 0.5rem;
}

.workflow-node-browser__section {
  margin-block-start: var(--sys-space-4);
}

.workflow-node-browser__section h3 {
  margin: 0 0 var(--sys-space-2);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-label-strong);
}

.workflow-node-browser__params,
.workflow-node-browser__returns {
  display: grid;
  margin: 0;
  gap: var(--sys-space-2);
  padding: 0;
  list-style: none;
}

.workflow-node-browser__params li,
.workflow-node-browser__returns li {
  display: grid;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.55rem;
  gap: 0.2rem;
  padding: var(--sys-space-2) var(--sys-space-3);
}

.workflow-node-browser__param-title {
  display: flex;
  align-items: center;
  font: var(--sys-typography-body-compact);
  gap: 0.4rem;
}

.workflow-node-browser__param-title em {
  border-radius: 999px;
  background: color-mix(in srgb, var(--sys-color-warning-text) 16%, transparent);
  color: var(--sys-color-warning-text);
  font: var(--sys-typography-caption);
  font-style: normal;
  padding: 0 0.35rem;
}

.workflow-node-browser__params small,
.workflow-node-browser__returns small {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  overflow-wrap: anywhere;
}

.workflow-node-browser__params p {
  margin: 0.15rem 0 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  line-height: 1.4;
}

.workflow-node-browser__detail-foot {
  display: flex;
  justify-content: flex-end;
  border-block-start: 1px solid var(--sys-color-border);
  padding: var(--sys-space-3) var(--sys-space-4);
}

.workflow-node-browser__empty {
  margin: 0;
  padding: 2rem var(--sys-space-4);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  text-align: center;
}

@media (max-width: 72rem) {
  .workflow-node-browser__body {
    grid-template-columns: minmax(11rem, 13rem) minmax(14rem, 1fr) minmax(16rem, 20rem);
  }
}

@media (max-width: 56rem) {
  .workflow-node-browser {
    padding: 0;
  }

  .workflow-node-browser__dialog {
    inline-size: 100%;
    block-size: 100dvh;
    border: 0;
    border-radius: 0;
  }

  .workflow-node-browser__header {
    flex-wrap: wrap;
  }

  .workflow-node-browser__brand {
    display: none;
  }

  .workflow-node-browser__scopes {
    flex: 1;
    justify-content: center;
  }

  .workflow-node-browser__search {
    order: 3;
    flex-basis: 100%;
  }

  .workflow-node-browser__filters {
    overflow-x: auto;
  }

  .workflow-node-browser__body {
    grid-template-columns: minmax(0, 1fr);
  }

  .workflow-node-browser__panel + .workflow-node-browser__panel {
    border-inline-start: 0;
  }

  .workflow-node-browser__panel.is-mobile-hidden {
    display: none;
  }
}
</style>
