import type { ConnectionRecord } from '@modules/connections'

import type { WorkflowNodeCatalog, WorkflowNodeCatalogItem } from '../api/workflow-api'

export type CatalogScope = 'all' | 'public' | 'adapter'

export interface CatalogGroup {
  key: string
  title: string
  items: WorkflowNodeCatalogItem[]
}

function adapterConnectionKeys(connections: ConnectionRecord[]): Set<string> {
  return new Set(
    connections
      .filter(
        (connection) =>
          connection.enabled && connection.pluginVersionId && connection.connectionType,
      )
      .map((connection) => `${connection.pluginVersionId}:${connection.connectionType}`),
  )
}

/** Non-adapter plugin nodes plus runtime system nodes. */
export function collectPublicCatalogItems(catalog: WorkflowNodeCatalog): WorkflowNodeCatalogItem[] {
  return [...catalog.systemNodes, ...catalog.nodes.filter((item) => !item.connectionType)]
}

/** Adapter nodes that have an enabled connection and are not blocked by the canvas lock. */
export function collectAdapterCatalogItems(
  catalog: WorkflowNodeCatalog,
  connections: ConnectionRecord[],
  lockedAdapterPluginVersionId?: string | null,
): WorkflowNodeCatalogItem[] {
  const keys = adapterConnectionKeys(connections)
  return catalog.nodes.filter((item) => {
    if (!item.connectionType || !item.pluginVersionId) return false
    if (!keys.has(`${item.pluginVersionId}:${item.connectionType}`)) return false
    if (lockedAdapterPluginVersionId && item.pluginVersionId !== lockedAdapterPluginVersionId) {
      return false
    }
    return item.nodeType === 'EVENT' || item.nodeType === 'ACTION'
  })
}

export function collectCatalogItems(
  catalog: WorkflowNodeCatalog,
  scope: CatalogScope,
  connections: ConnectionRecord[],
  lockedAdapterPluginVersionId?: string | null,
): WorkflowNodeCatalogItem[] {
  if (scope === 'public') return collectPublicCatalogItems(catalog)
  if (scope === 'adapter') {
    return collectAdapterCatalogItems(catalog, connections, lockedAdapterPluginVersionId)
  }
  return [
    ...collectPublicCatalogItems(catalog),
    ...collectAdapterCatalogItems(catalog, connections, lockedAdapterPluginVersionId),
  ]
}

export function groupCatalogItems(items: WorkflowNodeCatalogItem[]): CatalogGroup[] {
  const map = new Map<string, CatalogGroup>()
  for (const item of items) {
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
}

function catalogSearchText(item: WorkflowNodeCatalogItem): string {
  const parts: Array<string | null | undefined> = [
    item.name,
    item.description,
    item.nodeKey,
    item.pluginKey,
    item.pluginVersion,
    item.connectionType,
    item.sourceRef,
    item.category,
  ]
  for (const parameter of item.parameters ?? []) {
    parts.push(parameter.name, parameter.displayName, parameter.description, parameter.type)
  }
  for (const field of item.returnFields ?? []) {
    parts.push(field.name, field.displayName, field.path, field.description, field.type)
  }
  return parts.filter(Boolean).join(' ').toLocaleLowerCase('zh-CN')
}

/** Whitespace-separated tokens are ANDed, and each token may match any searchable field. */
export function matchesCatalogQuery(item: WorkflowNodeCatalogItem, query: string): boolean {
  const tokens = query.trim().toLocaleLowerCase('zh-CN').split(/\s+/u).filter(Boolean)
  if (!tokens.length) return true
  const haystack = catalogSearchText(item)
  return tokens.every((token) => haystack.includes(token))
}
