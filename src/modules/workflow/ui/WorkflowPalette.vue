<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Blocks, Cable, ChevronDown, ChevronRight, Search, X } from '@lucide/vue'

import type { ConnectionRecord } from '@modules/connections'

import type { WorkflowNodeCatalog, WorkflowNodeCatalogItem } from '../api/workflow-api'
import {
  catalogIdentity,
  catalogLabel,
  catalogNodeKind,
  catalogNodeTypeLabel,
  nodeAccent,
} from '../model/graph'

type PaletteKind = 'public' | 'adapter'

interface NodeGroup {
  key: string
  title: string
  items: WorkflowNodeCatalogItem[]
}

const props = defineProps<{
  catalog: WorkflowNodeCatalog
  connections: ConnectionRecord[]
  lockedAdapterPluginVersionId?: string | null
  loading?: boolean
}>()

defineEmits<{
  'start-drag': [event: DragEvent, item: WorkflowNodeCatalogItem]
  'end-drag': []
  add: [event: MouseEvent, item: WorkflowNodeCatalogItem]
  close: []
}>()

const activeKind = ref<PaletteKind>('public')
const keyword = ref('')
const expandedGroups = ref<Set<string>>(new Set())

const publicItems = computed(() => [
  ...props.catalog.systemNodes,
  ...props.catalog.nodes.filter((item) => !item.connectionType),
])
const adapterConnectionKeys = computed(
  () =>
    new Set(
      props.connections
        .filter(
          (connection) =>
            connection.enabled && connection.pluginVersionId && connection.connectionType,
        )
        .map((connection) => `${connection.pluginVersionId}:${connection.connectionType}`),
    ),
)

const adapterItems = computed(() =>
  props.catalog.nodes.filter((item) => {
    if (!item.connectionType || !item.pluginVersionId) return false
    if (!adapterConnectionKeys.value.has(`${item.pluginVersionId}:${item.connectionType}`)) {
      return false
    }
    if (
      props.lockedAdapterPluginVersionId &&
      item.pluginVersionId !== props.lockedAdapterPluginVersionId
    ) {
      return false
    }
    return item.nodeType === 'EVENT' || item.nodeType === 'ACTION'
  }),
)
const currentItems = computed(() =>
  activeKind.value === 'public' ? publicItems.value : adapterItems.value,
)
const emptyMessage = computed(() => {
  if (activeKind.value === 'public') return '没有匹配的节点'
  if (!props.connections.length) return '还没有已创建的协议适配器连接'
  if (props.lockedAdapterPluginVersionId) return '当前画布只显示已使用适配器的节点'
  return '没有与现有连接匹配的适配器节点'
})

const groups = computed<NodeGroup[]>(() => {
  const query = keyword.value.trim().toLocaleLowerCase('zh-CN')
  const map = new Map<string, NodeGroup>()
  for (const item of currentItems.value) {
    if (
      query &&
      !`${catalogLabel(item)} ${item.description ?? ''} ${item.nodeKey} ${item.connectionType ?? ''}`
        .toLocaleLowerCase('zh-CN')
        .includes(query)
    ) {
      continue
    }
    const isSystem = item.nodeKey.startsWith('system.')
    const key = isSystem ? 'system' : item.pluginKey || item.pluginVersionId || 'plugin'
    const title = isSystem ? '系统节点' : item.pluginKey || '插件节点'
    let group = map.get(key)
    if (!group) {
      group = { key, title, items: [] }
      map.set(key, group)
    }
    group.items.push(item)
  }
  return [...map.values()]
})

const toggleGroup = (key: string): void => {
  const next = new Set(expandedGroups.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedGroups.value = next
}

const isGroupOpen = (key: string): boolean => expandedGroups.value.has(key)

const selectKind = (kind: PaletteKind): void => {
  activeKind.value = kind
}

watch(
  groups,
  (nextGroups) => {
    const next = new Set(expandedGroups.value)
    nextGroups.forEach((group) => next.add(group.key))
    expandedGroups.value = next
  },
  { immediate: true },
)
</script>

<template>
  <!-- 滚轮停在面板里就是滚面板：画布整体的 @wheel.prevent 在祖先节点上，不拦住会把缩放抢走 -->
  <aside class="workflow-palette" @wheel.stop>
    <nav class="workflow-palette__rail" aria-label="节点类型">
      <button
        type="button"
        class="workflow-palette__rail-button"
        :class="{ 'is-active': activeKind === 'public' }"
        title="公共插件"
        aria-label="公共插件"
        @click="selectKind('public')"
      >
        <Blocks :size="19" />
      </button>
      <button
        type="button"
        class="workflow-palette__rail-button"
        :class="{ 'is-active': activeKind === 'adapter' }"
        title="适配器插件"
        aria-label="适配器插件"
        @click="selectKind('adapter')"
      >
        <Cable :size="19" />
      </button>
    </nav>

    <div class="workflow-palette__content">
      <header class="workflow-palette__header">
        <div>
          <strong>{{ activeKind === 'public' ? '公共插件' : '适配器插件' }}</strong>
          <small>
            {{
              activeKind === 'public'
                ? '只显示非协议适配器插件和系统节点'
                : lockedAdapterPluginVersionId
                  ? '已锁定当前画布使用的适配器'
                  : '只显示已创建连接对应的适配器'
            }}
          </small>
        </div>
        <span class="workflow-palette__count">{{ currentItems.length }}</span>
        <button
          type="button"
          class="workflow-palette__mobile-close"
          aria-label="关闭节点面板"
          @click="$emit('close')"
        >
          <X :size="18" />
        </button>
      </header>

      <label class="workflow-palette__search">
        <Search :size="15" aria-hidden="true" />
        <input v-model="keyword" type="search" placeholder="搜索节点" />
      </label>

      <div class="workflow-palette__scroll">
        <p v-if="loading" class="workflow-palette__state">正在加载节点…</p>
        <p v-else-if="!groups.length" class="workflow-palette__state">{{ emptyMessage }}</p>

        <template v-else>
          <section v-for="group in groups" :key="group.key" class="workflow-palette__group">
            <button
              type="button"
              class="workflow-palette__group-head"
              @click="toggleGroup(group.key)"
            >
              <component :is="isGroupOpen(group.key) ? ChevronDown : ChevronRight" :size="15" />
              <span>{{ group.title }}</span>
              <small>{{ group.items.length }}</small>
            </button>

            <div v-show="isGroupOpen(group.key)" class="workflow-palette__nodes">
              <button
                v-for="item in group.items"
                :key="catalogIdentity(item)"
                type="button"
                class="workflow-palette__node"
                :style="{ '--node-accent': nodeAccent(catalogNodeKind(item)) }"
                draggable="true"
                @dragstart="$emit('start-drag', $event, item)"
                @dragend="$emit('end-drag')"
                @click="$emit('add', $event, item)"
              >
                <span class="workflow-palette__node-main">
                  <strong>{{ catalogLabel(item) }}</strong>
                  <small>{{ item.description || item.nodeKey }}</small>
                </span>
                <span class="workflow-palette__node-meta">
                  <em>{{ catalogNodeTypeLabel(item) }}</em>
                  <small v-if="item.connectionType">{{ item.connectionType }}</small>
                </span>
              </button>
            </div>
          </section>
        </template>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.workflow-palette {
  position: absolute;
  z-index: 30;
  inset-block: 6.25rem 1rem;
  inset-inline-start: 1rem;
  display: flex;
  inline-size: min(19.5rem, calc(100vw - 2rem));
  min-block-size: 0;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--sys-color-border) 78%, transparent);
  border-radius: 0.75rem;
  background: color-mix(in srgb, var(--sys-color-surface-raised) 94%, transparent);
  box-shadow: var(--sys-shadow-raised);
  backdrop-filter: blur(1rem);
}

.workflow-palette__rail {
  display: flex;
  inline-size: 3rem;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: center;
  border-inline-end: 1px solid var(--sys-color-border);
  background: var(--sys-color-surface-muted);
  gap: var(--sys-space-2);
  padding-block: var(--sys-space-3);
}

.workflow-palette__rail-button {
  display: grid;
  inline-size: 2.25rem;
  block-size: 2.25rem;
  place-items: center;
  border: 1px solid transparent;
  border-radius: 0.65rem;
  background: transparent;
  color: var(--sys-color-text-muted);
  cursor: pointer;
}

.workflow-palette__rail-button:hover {
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-action-primary);
}

.workflow-palette__rail-button.is-active {
  border-color: color-mix(in srgb, var(--sys-color-action-primary) 26%, var(--sys-color-border));
  background: var(--sys-color-action-primary);
  color: var(--sys-color-on-action-primary);
}

.workflow-palette__content {
  display: flex;
  min-inline-size: 0;
  flex: 1;
  flex-direction: column;
}

.workflow-palette__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sys-space-3);
  border-block-end: 1px solid var(--sys-color-border);
  padding: var(--sys-space-4);
}

.workflow-palette__header strong {
  display: block;
  font: var(--sys-typography-label-strong);
}

.workflow-palette__header small {
  display: block;
  margin-block-start: 0.2rem;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.workflow-palette__count {
  min-inline-size: 1.6rem;
  border-radius: 999px;
  background: var(--sys-color-surface-muted);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-label);
  padding: 0.2rem 0.45rem;
  text-align: center;
}

.workflow-palette__mobile-close {
  display: none;
}

.workflow-palette__search {
  display: flex;
  align-items: center;
  gap: var(--sys-space-2);
  margin: var(--sys-space-3) var(--sys-space-3) var(--sys-space-2);
  border: 1px solid var(--sys-color-border-strong);
  border-radius: 0.65rem;
  background: var(--sys-color-field);
  color: var(--sys-color-text-muted);
  padding: 0 var(--sys-space-3);
}

.workflow-palette__search:focus-within {
  border-color: var(--sys-color-action-primary);
  box-shadow: 0 0 0 var(--sys-focus-width) var(--sys-color-focus);
}

.workflow-palette__search input {
  min-inline-size: 0;
  flex: 1;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--sys-color-text);
  font: var(--sys-typography-body-compact);
  padding-block: 0.55rem;
}

.workflow-palette__scroll {
  min-block-size: 0;
  flex: 1;
  overflow-y: auto;
  padding: 0 var(--sys-space-3) var(--sys-space-3);
}

.workflow-palette__state {
  margin: 0;
  padding: 2rem 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  text-align: center;
}

.workflow-palette__group + .workflow-palette__group {
  margin-block-start: var(--sys-space-2);
}

.workflow-palette__group-head {
  display: flex;
  inline-size: 100%;
  align-items: center;
  border: 0;
  border-radius: 0.55rem;
  background: transparent;
  color: var(--sys-color-text-muted);
  cursor: pointer;
  font: var(--sys-typography-label-strong);
  gap: var(--sys-space-2);
  padding: 0.55rem 0.4rem;
  text-align: start;
}

.workflow-palette__group-head:hover {
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-text);
}

.workflow-palette__group-head span {
  min-inline-size: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-palette__group-head small {
  color: var(--sys-color-text-subtle);
  font: var(--sys-typography-caption);
}

.workflow-palette__nodes {
  display: grid;
  gap: var(--sys-space-2);
}

.workflow-palette__node {
  display: grid;
  inline-size: 100%;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  border: 1px solid var(--sys-color-border);
  border-inline-start: 0.22rem solid var(--node-accent, var(--sys-color-action-primary));
  border-radius: 0.65rem;
  background: var(--sys-color-surface-raised);
  color: var(--sys-color-text);
  cursor: grab;
  gap: var(--sys-space-2);
  padding: var(--sys-space-3);
  text-align: start;
  transition:
    border-color var(--sys-motion-fast),
    box-shadow var(--sys-motion-fast),
    transform var(--sys-motion-fast);
}

.workflow-palette__node:hover {
  border-color: color-mix(
    in srgb,
    var(--node-accent, var(--sys-color-action-primary)) 42%,
    var(--sys-color-border)
  );
  box-shadow: 0 0.5rem 1.2rem
    color-mix(in srgb, var(--node-accent, var(--sys-color-action-primary)) 10%, transparent);
  transform: translateY(-1px);
}

.workflow-palette__node:active {
  cursor: grabbing;
}

.workflow-palette__node-main {
  min-inline-size: 0;
}

.workflow-palette__node-main strong,
.workflow-palette__node-main small {
  display: block;
}

.workflow-palette__node-main strong {
  overflow: hidden;
  font: var(--sys-typography-body-compact);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-palette__node-main small {
  margin-block-start: 0.2rem;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  line-height: 1.35;
}

.workflow-palette__node-meta {
  display: grid;
  justify-items: end;
  gap: 0.2rem;
}

.workflow-palette__node-meta em {
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

.workflow-palette__node-meta small {
  max-inline-size: 6.2rem;
  overflow: hidden;
  color: var(--sys-color-warning-text);
  font: var(--sys-typography-caption);
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 56rem) {
  .workflow-palette {
    inset: auto 0.75rem 0.75rem;
    inline-size: auto;
    max-block-size: min(68dvh, 38rem);
    border-radius: 1rem;
  }

  .workflow-palette__mobile-close {
    display: grid;
    inline-size: 2rem;
    block-size: 2rem;
    place-items: center;
    border: 1px solid var(--sys-color-border);
    border-radius: 50%;
    background: var(--sys-color-field);
    color: var(--sys-color-text-muted);
  }

  .workflow-palette__mobile-close:hover {
    background: var(--sys-color-action-subtle-hover);
    color: var(--sys-color-text);
  }
}
</style>
