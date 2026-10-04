<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import {
  ChevronDown,
  ChevronRight,
  CircleAlert,
  CornerDownRight,
  FolderTree,
  GripVertical,
  Pencil,
  Plus,
  Trash,
  X,
} from '@lucide/vue'

import { ApiError } from '@shared/api/http-client'
import { hasPermission } from '@shared/session/permissions'
import {
  BaseButton,
  BaseField,
  BaseNotice,
  BaseSurface,
  iconFor,
  iconKeys,
  iconLabels,
  message,
} from '@shared/ui'

import { useSessionStore } from '../../auth/model/session-store'
import { systemApi } from '../api/system-api'
import type { MenuItem, MenuPayload, MenuSortGroup, RoleMenuRelation } from '../model/types'

type MenuTab = 'structure' | 'assignment'
type PanelMode = 'detail' | 'create' | 'edit'
type DropZone = 'before' | 'after' | 'inside'

interface MenuNode extends MenuItem {
  children: MenuNode[]
}

interface MenuRow {
  node: MenuNode
  depth: number
}

/** 顶级菜单的 parent_id，和数据库里的约定一致。 */
const ROOT_ID = '0'

const session = useSessionStore()
const menus = ref<MenuItem[]>([])
const assignmentMenus = ref<MenuItem[]>([])
const roleMenus = ref<RoleMenuRelation[]>([])
const activeTab = ref<MenuTab>('structure')
const selectedRoleId = ref<string | null>(null)
/** 角色菜单 tab 的展开状态。和结构 tab 分开，一个 tab 的折叠不该影响另一个。 */
const openMenuGroups = ref<string[]>([])
const openStructureGroups = ref<string[]>([])
const query = ref('')
const isLoading = ref(true)
const isRefreshing = ref(false)
const isSaving = ref(false)
const deletingId = ref<string | null>(null)
/** 行内二次确认：用它替代 window.confirm，保持和全站组件语言一致。 */
const confirmDeleteId = ref<string | null>(null)
const selectedMenuId = ref<string | null>(null)
const panelMode = ref<PanelMode>('detail')
const draggingId = ref<string | null>(null)
const dropHint = ref<{ id: string; zone: DropZone } | null>(null)
const form = reactive<MenuPayload>(emptyPayload())
const loadedStructure = ref(false)
/**
 * 加载失败标记。
 *
 * 失败提示走 Message 提示条（几秒后消失），但提示条一消失，空的菜单树就会显示成
 * 「还没有任何菜单」—— 这是一句假话：不是没有菜单，是没读到。所以还要留一个
 * 常驻的失败态占位（含重试按钮）。判据见决策记录 D72：会过期的信息走提示条，
 * 需要一直解释当前状态的信息留在页面里。
 */
const loadFailed = ref(false)
const loadedAssignment = ref(false)

const canList = computed(() => hasPermission(session.user?.permissions, 'system:menu:list'))
const canCreate = computed(() => hasPermission(session.user?.permissions, 'system:menu:create'))
const canUpdate = computed(() => hasPermission(session.user?.permissions, 'system:menu:update'))
const canDelete = computed(() => hasPermission(session.user?.permissions, 'system:menu:delete'))
const canListRoleMenus = computed(() =>
  hasPermission(session.user?.permissions, 'system:role:list'),
)
const canUpdateRoleMenus = computed(() =>
  hasPermission(session.user?.permissions, 'system:role:update'),
)
const canAccess = computed(() => canList.value || canListRoleMenus.value)
const actorIsOwner = computed(() => Boolean(session.user?.roles?.includes('OWNER')))

const structureTree = computed(() => buildTree(menus.value))
const assignmentTree = computed(() =>
  buildTree(assignmentMenus.value.length ? assignmentMenus.value : menus.value),
)
const assignmentRows = computed(() =>
  flattenTree(assignmentTree.value, new Set(openMenuGroups.value)),
)

const selectedRole = computed(() =>
  roleMenus.value.find((role) => role.id === selectedRoleId.value),
)
const selectedRoleLocked = computed(
  () => selectedRole.value?.code === 'OWNER' && !actorIsOwner.value,
)
const selectedRoleMenuIds = computed<string[]>({
  get: () => selectedRole.value?.menuIds ?? [],
  set: (ids) => {
    if (selectedRole.value) selectedRole.value.menuIds = ids
  },
})

const selectedMenu = computed(
  () => menus.value.find((menu) => menu.id === selectedMenuId.value) ?? null,
)
const isSearching = computed(() => query.value.trim().length > 0)
const matchedMenuIds = computed<Set<string>>(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return new Set<string>()
  const matched = new Set<string>()
  menus.value.forEach((menu) => {
    const haystack = [
      menu.name,
      menu.routeName ?? '',
      menu.path ?? '',
      menu.componentKey ?? '',
      menu.iconKey ?? '',
    ]
    if (haystack.some((value) => value.toLowerCase().includes(keyword))) matched.add(menu.id)
  })
  return matched
})

const structureRows = computed<MenuRow[]>(() => {
  if (!isSearching.value) {
    return flattenTree(structureTree.value, new Set(openStructureGroups.value))
  }
  // 搜索时保留命中项的祖先路径：只把命中的子菜单拎出来，它就脱离了所属目录，反而看不出它在哪。
  const keep = new Set(matchedMenuIds.value)
  menus.value.forEach((menu) => {
    if (keep.has(menu.id)) addAncestors(menu.id, keep)
  })
  return flattenFiltered(structureTree.value, keep, 0)
})

const structureHint = computed(() => {
  if (isSearching.value) return '搜索中会保留命中项的父级路径，清空搜索后即可拖拽排序。'
  if (!canUpdate.value) return '当前账号没有调整菜单的权限。'
  return '拖动行调整顺序：落在行的上/下缘是同级重排，落在目录行中间会移入该目录。'
})

/** 编辑时不能把自己或自己的子孙选成父级，否则会成环（后端也会拒绝）。 */
const editingSubtreeIds = computed<Set<string>>(() =>
  panelMode.value === 'edit' && selectedMenuId.value
    ? subtreeIds(selectedMenuId.value)
    : new Set<string>(),
)
/**
 * 可选父级只列目录。
 *
 * <p>侧边栏只把「CATALOG 且有子节点」渲染成可展开分组，挂在菜单下面的子项不会出现在任何地方 ——
 * 让它当父级等于把菜单静默藏起来，所以这里直接不给选。
 */
const parentOptions = computed(() =>
  menus.value.filter((menu) => menu.type === 'CATALOG' && !editingSubtreeIds.value.has(menu.id)),
)

const routeFieldsRequired = computed(() => form.type === 'MENU')
const iconIsUnknown = computed(() => !isKnownIconKey(form.iconKey))
const canSubmit = computed(() => {
  if (!form.name.trim()) return false
  if (
    routeFieldsRequired.value &&
    (!form.routeName.trim() || !form.path.trim() || !form.componentKey.trim())
  ) {
    return false
  }
  return panelMode.value === 'create' ? canCreate.value : canUpdate.value
})

function invalidateAssignmentCache(): void {
  loadedAssignment.value = false
  assignmentMenus.value = []
}

function emptyPayload(): MenuPayload {
  return {
    parentId: ROOT_ID,
    type: 'MENU',
    name: '',
    routeName: '',
    path: '',
    componentKey: '',
    iconKey: '',
    redirect: '',
    sort: 0,
    visible: true,
    keepAlive: false,
    remark: '',
  }
}

function resetForm(): void {
  Object.assign(form, emptyPayload())
}

function isKnownIconKey(value: string): boolean {
  const key = value.trim().toLowerCase()
  return key.length === 0 || iconKeys.some((candidate) => candidate === key)
}

function buildTree(items: MenuItem[]): MenuNode[] {
  const nodes = new Map<string, MenuNode>()
  items.forEach((item) => nodes.set(item.id, { ...item, children: [] }))
  const roots: MenuNode[] = []
  nodes.forEach((node) => {
    const parent = node.parentId === ROOT_ID ? undefined : nodes.get(node.parentId)
    if (parent) parent.children.push(node)
    else roots.push(node)
  })
  const sortNodes = (entries: MenuNode[]): void => {
    entries.sort(
      (left, right) => left.sort - right.sort || left.name.localeCompare(right.name, 'zh-CN'),
    )
    for (const entry of entries) sortNodes(entry.children)
  }
  sortNodes(roots)
  return roots
}

function flattenTree(nodes: MenuNode[], expanded: Set<string>, depth = 0): MenuRow[] {
  const result: MenuRow[] = []
  nodes.forEach((node) => {
    result.push({ node, depth })
    if (node.children.length && expanded.has(node.id)) {
      result.push(...flattenTree(node.children, expanded, depth + 1))
    }
  })
  return result
}

/** 搜索时的行集合：命中项 + 它们的祖先，全部展开。 */
function flattenFiltered(nodes: MenuNode[], keep: Set<string>, depth: number): MenuRow[] {
  const result: MenuRow[] = []
  nodes.forEach((node) => {
    const children = flattenFiltered(node.children, keep, depth + 1)
    if (!keep.has(node.id) && children.length === 0) return
    result.push({ node, depth })
    result.push(...children)
  })
  return result
}

function findNode(nodes: MenuNode[], id: string): MenuNode | null {
  for (const node of nodes) {
    if (node.id === id) return node
    const found = findNode(node.children, id)
    if (found) return found
  }
  return null
}

function subtreeIds(id: string): Set<string> {
  const node = findNode(structureTree.value, id)
  if (!node) return new Set<string>()
  const ids = new Set<string>()
  const walk = (entry: MenuNode): void => {
    ids.add(entry.id)
    entry.children.forEach(walk)
  }
  walk(node)
  return ids
}

function addAncestors(id: string, into: Set<string>): void {
  const menu = menus.value.find((item) => item.id === id)
  if (!menu || menu.parentId === ROOT_ID) return
  into.add(menu.parentId)
  addAncestors(menu.parentId, into)
}

function siblingIds(parentId: string): string[] {
  const siblings =
    parentId === ROOT_ID
      ? structureTree.value
      : (findNode(structureTree.value, parentId)?.children ?? [])
  return siblings.map((node) => node.id)
}

function childCount(id: string): number {
  return menus.value.filter((menu) => menu.parentId === id).length
}

function parentLabel(parentId: string): string {
  if (parentId === ROOT_ID) return '顶级菜单'
  return menus.value.find((menu) => menu.id === parentId)?.name ?? '未知父级'
}

/**
 * 侧边栏可见性。
 *
 * 原来这里是「enabled 和 visible 两个开关合起来说」。菜单的 status 删掉之后（见
 * `V1__bootstrap_schema.sql` 末尾），只剩 `visible` 一个字段决定这件事，于是这段解释性
 * 文案也可以直接说结论了。
 */
function sidebarVisibility(menu: MenuItem): string {
  return menu.visible ? '出现在侧边栏' : '不出现在侧边栏（已隐藏）'
}

function isStructureOpen(node: MenuNode): boolean {
  return openStructureGroups.value.includes(node.id)
}

function toggleStructureGroup(node: MenuNode): void {
  openStructureGroups.value = isStructureOpen(node)
    ? openStructureGroups.value.filter((id) => id !== node.id)
    : [...openStructureGroups.value, node.id]
}

function expandGroup(id: string): void {
  if (id === ROOT_ID || openStructureGroups.value.includes(id)) return
  openStructureGroups.value = [...openStructureGroups.value, id]
}

function openDetail(menu: MenuItem): void {
  if (panelMode.value === 'edit') resetForm()
  selectedMenuId.value = menu.id
  panelMode.value = 'detail'
}

function startEdit(menu: MenuItem): void {
  selectedMenuId.value = menu.id
  panelMode.value = 'edit'
  Object.assign(form, {
    parentId: menu.parentId,
    type: menu.type,
    name: menu.name,
    routeName: menu.routeName ?? '',
    path: menu.path ?? '',
    componentKey: menu.componentKey ?? '',
    iconKey: menu.iconKey ?? '',
    redirect: menu.redirect ?? '',
    sort: menu.sort,
    visible: menu.visible,
    keepAlive: menu.keepAlive,
    remark: menu.remark ?? '',
  })
}

function openCreate(parentId: string = ROOT_ID): void {
  resetForm()
  form.parentId = parentId
  selectedMenuId.value = null
  panelMode.value = 'create'
}

function closePanel(): void {
  resetForm()
  panelMode.value = 'detail'
}

async function loadStructure(): Promise<void> {
  if (!canList.value || loadedStructure.value) return
  isRefreshing.value = true
  try {
    menus.value = await systemApi.listMenus()
    loadedStructure.value = true
    if (openStructureGroups.value.length === 0) {
      openStructureGroups.value = menus.value
        .filter((menu) => menu.type === 'CATALOG')
        .map((menu) => menu.id)
    }
    loadFailed.value = false
  } catch (error) {
    loadFailed.value = true
    message.error(error instanceof ApiError ? error.message : '菜单数据加载失败，请稍后重试')
  } finally {
    isLoading.value = false
    isRefreshing.value = false
  }
}

async function loadAssignment(): Promise<void> {
  if (!canListRoleMenus.value || loadedAssignment.value) return
  isRefreshing.value = true
  try {
    const [nextRoles, nextMenus] = await Promise.all([
      systemApi.listRoleMenus(),
      assignmentMenus.value.length || menus.value.length
        ? Promise.resolve(assignmentMenus.value.length ? assignmentMenus.value : menus.value)
        : systemApi.listRelationMenus(),
    ])
    roleMenus.value = nextRoles
    assignmentMenus.value = nextMenus
    loadedAssignment.value = true
    if (!selectedRoleId.value) {
      // 非站长默认落在一个可编辑的角色上，而不是被锁住的 OWNER
      const preferred = roleMenus.value.find((role) => actorIsOwner.value || role.code !== 'OWNER')
      selectedRoleId.value = preferred?.id ?? roleMenus.value[0]?.id ?? null
    }
    if (openMenuGroups.value.length === 0) {
      openMenuGroups.value = buildTree(nextMenus)
        .filter((node) => node.children.length)
        .map((node) => node.id)
    }
    loadFailed.value = false
  } catch (error) {
    loadFailed.value = true
    message.error(error instanceof ApiError ? error.message : '角色菜单数据加载失败，请稍后重试')
  } finally {
    isLoading.value = false
    isRefreshing.value = false
  }
}

async function loadCurrentTab(force = false): Promise<void> {
  if (!session.user) await session.loadProfile()
  if (force) {
    if (activeTab.value === 'structure') loadedStructure.value = false
    else loadedAssignment.value = false
  }
  if (activeTab.value === 'structure') {
    if (loadedStructure.value) {
      isLoading.value = false
      isRefreshing.value = false
      return
    }
    await loadStructure()
  } else {
    if (loadedAssignment.value) {
      isLoading.value = false
      isRefreshing.value = false
      return
    }
    await loadAssignment()
  }
}

async function switchTab(tab: MenuTab): Promise<void> {
  if (activeTab.value === tab) return
  activeTab.value = tab
  isLoading.value = true
  try {
    await loadCurrentTab()
  } catch (error) {
    loadFailed.value = true
    message.error(error instanceof ApiError ? error.message : '菜单数据加载失败，请稍后重试')
    isLoading.value = false
    isRefreshing.value = false
  }
}

async function saveMenu(): Promise<void> {
  if (!canSubmit.value) return
  const creating = panelMode.value === 'create'
  isSaving.value = true
  try {
    const saved = creating
      ? await systemApi.createMenu({ ...form })
      : await systemApi.updateMenu(selectedMenuId.value ?? '', { ...form })
    if (creating) menus.value.push(saved)
    else {
      const index = menus.value.findIndex((menu) => menu.id === saved.id)
      if (index >= 0) menus.value[index] = saved
    }
    invalidateAssignmentCache()
    expandGroup(saved.parentId)
    message.success(creating ? '菜单已创建' : '菜单已更新')
    resetForm()
    selectedMenuId.value = saved.id
    panelMode.value = 'detail'
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '菜单保存失败，请稍后重试')
  } finally {
    isSaving.value = false
  }
}

function requestDelete(menu: MenuItem): void {
  if (!canDelete.value || childCount(menu.id) > 0) return
  confirmDeleteId.value = menu.id
}

function cancelDelete(): void {
  confirmDeleteId.value = null
}

async function removeMenu(menu: MenuItem): Promise<void> {
  // 有子节点时后端必定拒绝，前端直接不给点，省一次注定失败的请求
  if (!canDelete.value || deletingId.value !== null || childCount(menu.id) > 0) return
  deletingId.value = menu.id
  try {
    await systemApi.deleteMenu(menu.id)
    menus.value = menus.value.filter((item) => item.id !== menu.id)
    invalidateAssignmentCache()
    if (selectedMenuId.value === menu.id) {
      selectedMenuId.value = null
      panelMode.value = 'detail'
    }
    resetForm()
    message.success('菜单已删除')
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '菜单删除失败，请稍后重试')
  } finally {
    deletingId.value = null
    confirmDeleteId.value = null
  }
}

function onDragStart(node: MenuNode, event: DragEvent): void {
  if (!canUpdate.value || isSearching.value) {
    event.preventDefault()
    return
  }
  draggingId.value = node.id
  dropHint.value = null
  event.dataTransfer?.setData('text/plain', node.id)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}

function onDragOver(node: MenuNode, event: DragEvent): void {
  const dragId = draggingId.value
  if (!dragId || dragId === node.id) return
  // 拖进自己的子树会成环，后端也会拒绝；这里直接不给落点，避免「拖过去又被弹回来」
  if (subtreeIds(dragId).has(node.id)) return

  const element = event.currentTarget
  if (!(element instanceof HTMLElement)) return
  const rect = element.getBoundingClientRect()
  const ratio = rect.height > 0 ? (event.clientY - rect.top) / rect.height : 0.5
  let zone: DropZone = ratio < 0.3 ? 'before' : ratio > 0.7 ? 'after' : 'inside'
  // 只有目录能装子节点（见 parentOptions 的说明）
  if (zone === 'inside' && node.type !== 'CATALOG') {
    zone = ratio < 0.5 ? 'before' : 'after'
  }
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  dropHint.value = { id: node.id, zone }
}

function onDragLeave(node: MenuNode): void {
  if (dropHint.value?.id === node.id) dropHint.value = null
}

function onDrop(node: MenuNode, event: DragEvent): void {
  event.preventDefault()
  const hint = dropHint.value
  const dragId = draggingId.value
  resetDrag()
  if (!hint || !dragId || hint.id !== node.id) return
  void applyMove(dragId, node, hint.zone)
}

function resetDrag(): void {
  draggingId.value = null
  dropHint.value = null
}

/**
 * 把一次拖拽落成一个排序请求。
 *
 * <p>拖拽会同时影响两个层级（旧父级少一项、新父级多一项），两组一起提交，后端在同一个事务里落库；
 * 只提交新父级会让旧父级留下序号空洞，「从哪挪走的」这件事在数据上也看不出来。
 */
async function applyMove(dragId: string, target: MenuNode, zone: DropZone): Promise<void> {
  const dragged = findNode(structureTree.value, dragId)
  if (!dragged) return
  const oldParentId = dragged.parentId
  const newParentId = zone === 'inside' ? target.id : target.parentId

  const oldSiblings = siblingIds(oldParentId).filter((id) => id !== dragId)
  const newSiblings = siblingIds(newParentId).filter((id) => id !== dragId)
  if (zone === 'inside') {
    newSiblings.push(dragId)
  } else {
    const index = newSiblings.indexOf(target.id)
    const insertAt = index < 0 ? newSiblings.length : zone === 'before' ? index : index + 1
    newSiblings.splice(insertAt, 0, dragId)
  }

  const groups = new Map<string, string[]>()
  if (oldParentId !== newParentId) groups.set(oldParentId, oldSiblings)
  groups.set(newParentId, newSiblings)

  isSaving.value = true
  try {
    const payload: MenuSortGroup[] = [...groups.entries()].map(([parentId, ids]) => ({
      parentId,
      ids,
    }))
    await systemApi.sortMenus(payload)
    menus.value = await systemApi.listMenus()
    invalidateAssignmentCache()
    expandGroup(newParentId)
    message.success('菜单顺序已更新')
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '菜单排序失败，请稍后重试')
  } finally {
    isSaving.value = false
  }
}

function isMenuOpen(node: MenuNode): boolean {
  return openMenuGroups.value.includes(node.id)
}

function toggleMenuGroup(node: MenuNode): void {
  openMenuGroups.value = isMenuOpen(node)
    ? openMenuGroups.value.filter((id) => id !== node.id)
    : [...openMenuGroups.value, node.id]
}

function isMenuSelected(id: string): boolean {
  return selectedRoleMenuIds.value.includes(id)
}

function collectMenuIds(node: MenuNode): string[] {
  return [node.id, ...node.children.flatMap((child) => collectMenuIds(child))]
}

function menuItemsForAssignment(): MenuItem[] {
  return assignmentMenus.value.length ? assignmentMenus.value : menus.value
}

function addMenuAncestors(id: string, ids: Set<string>): void {
  const menu = menuItemsForAssignment().find((item) => item.id === id)
  if (!menu || menu.parentId === ROOT_ID) return
  ids.add(menu.parentId)
  addMenuAncestors(menu.parentId, ids)
}

function setMenuSelection(node: MenuNode, checked: boolean): void {
  const ids = new Set(selectedRoleMenuIds.value)
  collectMenuIds(node).forEach((id) => (checked ? ids.add(id) : ids.delete(id)))
  if (checked) addMenuAncestors(node.id, ids)
  selectedRoleMenuIds.value = [...ids]
}

function handleMenuChange(node: MenuNode, event: Event): void {
  const target = event.target
  if (target instanceof HTMLInputElement) setMenuSelection(node, target.checked)
}

async function saveRoleMenus(): Promise<void> {
  if (!selectedRole.value || !canUpdateRoleMenus.value || selectedRoleLocked.value) return
  isSaving.value = true
  try {
    await systemApi.updateRoleMenus(selectedRole.value.id, selectedRoleMenuIds.value)
    message.success('角色菜单已保存')
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '角色菜单保存失败，请稍后重试')
  } finally {
    isSaving.value = false
  }
}

onMounted(async () => {
  try {
    if (!session.user) await session.loadProfile()
    if (!canList.value && canListRoleMenus.value) activeTab.value = 'assignment'
    await loadCurrentTab()
  } catch (error) {
    loadFailed.value = true
    message.error(error instanceof ApiError ? error.message : '菜单数据加载失败，请稍后重试')
    isLoading.value = false
    isRefreshing.value = false
  }
})
</script>

<template>
  <main class="system-page menu-page">
    <div class="system-page__shell">
      <header class="system-page__header">
        <div>
          <span class="system-page__eyebrow">System / Navigation</span>
          <h1>菜单管理</h1>
          <p>维护菜单树、路由配置和角色菜单分配。菜单控制入口可见性，操作权限请前往权限管理。</p>
        </div>
        <div class="system-page__actions">
          <BaseButton
            appearance="secondary"
            size="small"
            :loading="isRefreshing"
            @click="loadCurrentTab(true)"
          >
            刷新
          </BaseButton>
        </div>
      </header>

      <nav v-if="canAccess" class="menu-tabs" aria-label="菜单管理分区">
        <button
          v-if="canList"
          type="button"
          class="menu-tabs__item"
          :class="{ 'menu-tabs__item--active': activeTab === 'structure' }"
          @click="switchTab('structure')"
        >
          菜单结构
          <small>维护目录与路由</small>
        </button>
        <button
          v-if="canListRoleMenus"
          type="button"
          class="menu-tabs__item"
          :class="{ 'menu-tabs__item--active': activeTab === 'assignment' }"
          @click="switchTab('assignment')"
        >
          角色菜单
          <small>控制入口可见性</small>
        </button>
      </nav>

      <BaseSurface v-if="isLoading" padding="large">
        <p class="system-page__empty">正在读取菜单数据…</p>
      </BaseSurface>

      <BaseNotice v-else-if="!canAccess" tone="warning" title="没有权限">
        当前用户没有查看菜单的权限，请联系站长或管理员授权。
      </BaseNotice>

      <template v-else-if="activeTab === 'structure' && canList">
        <div class="menu-structure">
          <BaseSurface padding="none" class="menu-structure__tree">
            <div class="menu-structure__toolbar">
              <label class="system-page__filter">
                <span>筛选菜单</span>
                <input v-model="query" type="search" placeholder="按名称、路由、组件或图标搜索" />
              </label>
              <div class="menu-structure__toolbar-meta">
                <span class="system-page__count">
                  {{
                    isSearching
                      ? `${matchedMenuIds.size} / ${menus.length} 项匹配`
                      : `${menus.length} 项`
                  }}
                </span>
                <BaseButton v-if="canCreate" size="small" @click="openCreate(ROOT_ID)">
                  <component :is="Plus" :size="15" />
                  新建菜单
                </BaseButton>
              </div>
            </div>

            <p class="menu-structure__hint">{{ structureHint }}</p>

            <div v-if="menus.length" class="menu-tree">
              <div
                v-for="row in structureRows"
                :key="row.node.id"
                class="menu-tree__row"
                :class="{
                  'menu-tree__row--selected': row.node.id === selectedMenuId,
                  'menu-tree__row--dragging': row.node.id === draggingId,
                  'menu-tree__row--hidden': !row.node.visible,
                  'menu-tree__row--drop-before':
                    dropHint?.id === row.node.id && dropHint.zone === 'before',
                  'menu-tree__row--drop-after':
                    dropHint?.id === row.node.id && dropHint.zone === 'after',
                  'menu-tree__row--drop-inside':
                    dropHint?.id === row.node.id && dropHint.zone === 'inside',
                }"
                :style="{ '--tree-depth': row.depth }"
                :draggable="canUpdate && !isSearching"
                @click="openDetail(row.node)"
                @dragstart="onDragStart(row.node, $event)"
                @dragover="onDragOver(row.node, $event)"
                @dragleave="onDragLeave(row.node)"
                @drop="onDrop(row.node, $event)"
                @dragend="resetDrag"
              >
                <span v-if="canUpdate && !isSearching" class="menu-tree__handle" aria-hidden="true">
                  <component :is="GripVertical" :size="14" />
                </span>
                <button
                  v-if="row.node.children.length"
                  type="button"
                  class="menu-tree__twisty"
                  :aria-label="isStructureOpen(row.node) ? '收起子菜单' : '展开子菜单'"
                  @click.stop="toggleStructureGroup(row.node)"
                >
                  <component
                    :is="isStructureOpen(row.node) ? ChevronDown : ChevronRight"
                    :size="15"
                  />
                </button>
                <span v-else class="menu-tree__twisty menu-tree__twisty--leaf" aria-hidden="true" />

                <span class="menu-tree__icon" aria-hidden="true">
                  <component
                    :is="iconFor(row.node.iconKey, row.node.type === 'CATALOG' ? 'layout' : 'menu')"
                    :size="16"
                  />
                </span>

                <span class="menu-tree__copy">
                  <strong :class="{ 'menu-tree__match': matchedMenuIds.has(row.node.id) }">
                    {{ row.node.name }}
                  </strong>
                  <small>{{ row.node.routeName || row.node.path || '目录节点' }}</small>
                </span>

                <span class="menu-tree__tags">
                  <span class="menu-tree__tag">
                    {{ row.node.type === 'CATALOG' ? '目录' : '菜单' }}
                  </span>
                  <span v-if="!row.node.visible" class="menu-tree__tag menu-tree__tag--muted">
                    隐藏
                  </span>
                </span>

                <span class="menu-tree__actions" @click.stop>
                  <template v-if="confirmDeleteId === row.node.id">
                    <span class="menu-tree__confirm">确定删除？</span>
                    <BaseButton
                      appearance="danger"
                      size="small"
                      :loading="deletingId === row.node.id"
                      @click="removeMenu(row.node)"
                    >
                      删除
                    </BaseButton>
                    <BaseButton appearance="ghost" size="small" @click="cancelDelete">
                      取消
                    </BaseButton>
                  </template>
                  <template v-else>
                    <BaseButton
                      v-if="canCreate && row.node.type === 'CATALOG'"
                      appearance="ghost"
                      size="small"
                      :title="`在「${row.node.name}」下新建`"
                      :aria-label="`在「${row.node.name}」下新建`"
                      @click="openCreate(row.node.id)"
                    >
                      <component :is="Plus" :size="15" />
                    </BaseButton>
                    <BaseButton
                      v-if="canUpdate"
                      appearance="ghost"
                      size="small"
                      title="编辑"
                      aria-label="编辑"
                      @click="startEdit(row.node)"
                    >
                      <component :is="Pencil" :size="15" />
                    </BaseButton>
                    <BaseButton
                      v-if="canDelete"
                      appearance="ghost"
                      size="small"
                      :disabled="childCount(row.node.id) > 0"
                      :title="
                        childCount(row.node.id) > 0
                          ? '先删除或移走子菜单'
                          : `删除「${row.node.name}」`
                      "
                      :aria-label="`删除「${row.node.name}」`"
                      @click="requestDelete(row.node)"
                    >
                      <component :is="Trash" :size="15" />
                    </BaseButton>
                  </template>
                </span>
              </div>
              <p v-if="!structureRows.length" class="system-page__empty">
                没有匹配的菜单，换个关键词试试。
              </p>
            </div>

            <div v-else-if="loadFailed" class="menu-structure__empty">
              <component :is="CircleAlert" :size="20" aria-hidden="true" />
              <div>
                <strong>菜单数据没能加载出来</strong>
                <p>上面的提示条几秒后就消失了，这里留一个一直在的重试入口。</p>
                <BaseButton size="small" :loading="isRefreshing" @click="loadCurrentTab(true)">
                  重试
                </BaseButton>
              </div>
            </div>

            <div v-else class="menu-structure__empty">
              <component :is="CircleAlert" :size="20" aria-hidden="true" />
              <div>
                <strong>还没有任何菜单</strong>
                <p>
                  建议先建一个「目录」作为分组，再在目录下挂具体页面。侧边栏按
                  <code>目录 → 菜单</code> 两级渲染，挂在菜单下的子项不会显示。
                </p>
                <BaseButton v-if="canCreate" size="small" @click="openCreate(ROOT_ID)">
                  <component :is="FolderTree" :size="15" />
                  新建目录
                </BaseButton>
              </div>
            </div>
          </BaseSurface>

          <BaseSurface padding="medium" class="menu-inspector">
            <template v-if="panelMode === 'create' || panelMode === 'edit'">
              <div class="menu-page__section-heading">
                <div>
                  <span class="menu-page__section-kicker">
                    {{ panelMode === 'create' ? '新增节点' : '编辑节点' }}
                  </span>
                  <h2>{{ panelMode === 'create' ? '创建菜单' : '修改菜单' }}</h2>
                </div>
                <button class="menu-page__text-button" type="button" @click="closePanel">
                  取消
                </button>
              </div>

              <form class="menu-page__form" @submit.prevent="saveMenu">
                <div class="menu-page__form-grid">
                  <BaseField
                    v-model="form.name"
                    label="菜单名称"
                    name="menu-name"
                    placeholder="例如：权限管理"
                    required
                  />
                  <label
                    class="menu-page__native-field"
                    title="菜单 = 可点击进入的页面；目录 = 只做分组，侧边栏里展开子项"
                  >
                    <span>菜单类型</span>
                    <select v-model="form.type">
                      <option value="MENU">菜单（页面）</option>
                      <option value="CATALOG">目录（分组）</option>
                    </select>
                  </label>
                  <label class="menu-page__native-field">
                    <span>父级菜单</span>
                    <select v-model="form.parentId">
                      <option :value="ROOT_ID">顶级菜单</option>
                      <option v-for="menu in parentOptions" :key="menu.id" :value="menu.id">
                        {{ menu.name }}
                      </option>
                    </select>
                  </label>
                  <label class="menu-page__native-field">
                    <span>排序序号</span>
                    <input v-model.number="form.sort" type="number" min="0" max="9999" />
                  </label>

                  <template v-if="routeFieldsRequired">
                    <BaseField
                      v-model="form.routeName"
                      label="路由名称"
                      name="menu-route-name"
                      placeholder="system-permissions"
                      required
                    />
                    <BaseField
                      v-model="form.path"
                      label="路由地址"
                      name="menu-path"
                      placeholder="/system/permissions"
                      required
                    />
                    <BaseField
                      v-model="form.componentKey"
                      class="menu-page__field-wide"
                      label="组件注册 key"
                      name="menu-component"
                      placeholder="system.permissions"
                      required
                    />
                  </template>
                  <template v-else>
                    <BaseField
                      v-model="form.path"
                      label="目录地址（可选）"
                      name="menu-path"
                      placeholder="/system"
                    />
                    <BaseField
                      v-model="form.redirect"
                      label="默认跳转（可选）"
                      name="menu-redirect"
                      placeholder="/system/permissions"
                    />
                  </template>

                  <BaseField
                    v-model="form.remark"
                    class="menu-page__field-wide"
                    label="备注"
                    name="menu-remark"
                    placeholder="可选说明"
                  />
                </div>

                <div class="menu-icon-picker">
                  <span class="menu-icon-picker__label">图标</span>
                  <div class="menu-icon-picker__grid">
                    <button
                      type="button"
                      class="menu-icon-picker__option"
                      :class="{ 'menu-icon-picker__option--active': form.iconKey === '' }"
                      title="不设置图标"
                      aria-label="不设置图标"
                      @click="form.iconKey = ''"
                    >
                      <component :is="X" :size="15" />
                    </button>
                    <button
                      v-for="key in iconKeys"
                      :key="key"
                      type="button"
                      class="menu-icon-picker__option"
                      :class="{ 'menu-icon-picker__option--active': form.iconKey === key }"
                      :title="iconLabels[key]"
                      :aria-label="iconLabels[key]"
                      @click="form.iconKey = key"
                    >
                      <component :is="iconFor(key)" :size="16" />
                    </button>
                  </div>
                  <p v-if="iconIsUnknown" class="menu-icon-picker__warning">
                    <component :is="CircleAlert" :size="14" aria-hidden="true" />
                    “{{ form.iconKey }}” 不在图标注册表里，侧边栏会回落到默认图标。
                  </p>
                </div>

                <div class="menu-page__checks">
                  <label><input v-model="form.visible" type="checkbox" /> 显示在侧边栏</label>
                  <label><input v-model="form.keepAlive" type="checkbox" /> 缓存页面</label>
                </div>

                <div class="menu-page__actions">
                  <BaseButton type="submit" :loading="isSaving" :disabled="!canSubmit">
                    {{ panelMode === 'create' ? '创建菜单' : '保存修改' }}
                  </BaseButton>
                  <BaseButton appearance="ghost" @click="closePanel">取消</BaseButton>
                </div>
              </form>
            </template>

            <template v-else-if="selectedMenu">
              <div class="menu-inspector__head">
                <span class="menu-inspector__icon" aria-hidden="true">
                  <component
                    :is="
                      iconFor(
                        selectedMenu.iconKey,
                        selectedMenu.type === 'CATALOG' ? 'layout' : 'menu',
                      )
                    "
                    :size="18"
                  />
                </span>
                <div>
                  <h2>{{ selectedMenu.name }}</h2>
                  <span class="menu-page__section-kicker">
                    {{ selectedMenu.type === 'CATALOG' ? '目录' : '菜单' }}
                  </span>
                </div>
              </div>

              <dl class="menu-inspector__facts">
                <div>
                  <dt>侧边栏</dt>
                  <dd>{{ sidebarVisibility(selectedMenu) }}</dd>
                </div>
                <div>
                  <dt>父级</dt>
                  <dd>{{ parentLabel(selectedMenu.parentId) }}</dd>
                </div>
                <div>
                  <dt>路由名称</dt>
                  <dd>{{ selectedMenu.routeName || '—' }}</dd>
                </div>
                <div>
                  <dt>路由地址</dt>
                  <dd>{{ selectedMenu.path || '—' }}</dd>
                </div>
                <div>
                  <dt>组件 key</dt>
                  <dd>{{ selectedMenu.componentKey || '—' }}</dd>
                </div>
                <div>
                  <dt>图标 key</dt>
                  <dd>{{ selectedMenu.iconKey || '—' }}</dd>
                </div>
                <div>
                  <dt>默认跳转</dt>
                  <dd>{{ selectedMenu.redirect || '—' }}</dd>
                </div>
                <div>
                  <dt>排序序号</dt>
                  <dd>{{ selectedMenu.sort }}</dd>
                </div>
                <div>
                  <dt>缓存页面</dt>
                  <dd>{{ selectedMenu.keepAlive ? '是' : '否' }}</dd>
                </div>
                <div>
                  <dt>子菜单</dt>
                  <dd>{{ childCount(selectedMenu.id) }} 项</dd>
                </div>
                <div v-if="selectedMenu.remark" class="menu-inspector__facts-wide">
                  <dt>备注</dt>
                  <dd>{{ selectedMenu.remark }}</dd>
                </div>
              </dl>

              <div class="menu-inspector__actions">
                <BaseButton v-if="canUpdate" size="small" @click="startEdit(selectedMenu)">
                  <component :is="Pencil" :size="15" />
                  编辑
                </BaseButton>
                <BaseButton
                  v-if="canCreate"
                  appearance="secondary"
                  size="small"
                  @click="
                    openCreate(
                      selectedMenu.type === 'CATALOG' ? selectedMenu.id : selectedMenu.parentId,
                    )
                  "
                >
                  <component :is="Plus" :size="15" />
                  {{ selectedMenu.type === 'CATALOG' ? '新建子菜单' : '新建同级' }}
                </BaseButton>
                <template v-if="canDelete && confirmDeleteId === selectedMenu.id">
                  <BaseButton
                    appearance="danger"
                    size="small"
                    :loading="deletingId === selectedMenu.id"
                    @click="removeMenu(selectedMenu)"
                  >
                    确认删除
                  </BaseButton>
                  <BaseButton appearance="ghost" size="small" @click="cancelDelete">
                    取消
                  </BaseButton>
                </template>
                <BaseButton
                  v-else-if="canDelete"
                  appearance="ghost"
                  size="small"
                  :disabled="childCount(selectedMenu.id) > 0"
                  :title="childCount(selectedMenu.id) > 0 ? '先删除或移走子菜单' : '删除'"
                  @click="requestDelete(selectedMenu)"
                >
                  <component :is="Trash" :size="15" />
                  删除
                </BaseButton>
              </div>
              <p v-if="canDelete && childCount(selectedMenu.id) > 0" class="menu-inspector__note">
                该项下还有 {{ childCount(selectedMenu.id) }} 个子菜单，需要先删除或移走它们。
              </p>
            </template>

            <div v-else class="menu-inspector__empty">
              <component :is="CornerDownRight" :size="18" aria-hidden="true" />
              <p>从左侧选择一个菜单查看详情，或者新建一个。</p>
              <BaseButton v-if="canCreate" size="small" @click="openCreate(ROOT_ID)">
                <component :is="Plus" :size="15" />
                新建菜单
              </BaseButton>
            </div>
          </BaseSurface>
        </div>
      </template>

      <template v-else-if="activeTab === 'assignment' && canListRoleMenus">
        <div class="menu-assignment-layout">
          <BaseSurface padding="medium" class="menu-assignment-layout__sidebar">
            <div class="menu-page__section-heading">
              <div>
                <span class="menu-page__section-kicker">Role menus</span>
                <h2>角色</h2>
              </div>
              <span class="system-page__count">{{ roleMenus.length }}</span>
            </div>
            <div class="menu-assignment__roles">
              <button
                v-for="role in roleMenus"
                :key="role.id"
                type="button"
                class="menu-assignment__role"
                :class="{ 'menu-assignment__role--active': role.id === selectedRoleId }"
                @click="selectedRoleId = role.id"
              >
                <strong>{{ role.name }}</strong>
                <span>{{ role.code }}</span>
              </button>
            </div>
          </BaseSurface>

          <BaseSurface v-if="selectedRole" padding="medium" class="menu-assignment-layout__content">
            <div class="menu-page__section-heading">
              <div>
                <span class="menu-page__section-kicker">Visible navigation</span>
                <h2>{{ selectedRole.name }} · 可见菜单</h2>
              </div>
              <BaseButton
                v-if="canUpdateRoleMenus && !selectedRoleLocked"
                size="small"
                :loading="isSaving"
                @click="saveRoleMenus"
              >
                保存菜单
              </BaseButton>
            </div>
            <BaseNotice v-if="selectedRoleLocked" tone="warning" title="仅站长可改">
              站长角色的可见菜单只有站长本人能调整，这里仅可查看。
            </BaseNotice>
            <div class="menu-assignment__hint">
              勾选页面或目录。选择子菜单时，保存会自动保留它所需的父级目录。
            </div>
            <div class="menu-assignment__tree">
              <div
                v-for="row in assignmentRows"
                :key="row.node.id"
                class="menu-assignment__row"
                :style="{ paddingInlineStart: `calc(${row.depth} * var(--sys-space-5))` }"
              >
                <button
                  v-if="row.node.children.length"
                  type="button"
                  class="menu-assignment__expand"
                  :aria-label="isMenuOpen(row.node) ? '收起目录' : '展开目录'"
                  @click="toggleMenuGroup(row.node)"
                >
                  <component :is="isMenuOpen(row.node) ? ChevronDown : ChevronRight" :size="15" />
                </button>
                <span v-else class="menu-assignment__expand-placeholder" aria-hidden="true" />
                <input
                  type="checkbox"
                  :checked="isMenuSelected(row.node.id)"
                  :disabled="!canUpdateRoleMenus || selectedRoleLocked"
                  @change="handleMenuChange(row.node, $event)"
                />
                <span class="menu-assignment__copy">
                  <strong>{{ row.node.name }}</strong>
                  <code>{{ row.node.path || '目录节点' }}</code>
                </span>
              </div>
              <p v-if="!assignmentRows.length" class="system-page__empty">暂无可分配菜单。</p>
            </div>
          </BaseSurface>
          <BaseSurface
            v-else-if="loadFailed"
            padding="large"
            class="menu-assignment-layout__content"
          >
            <p class="system-page__empty">角色菜单数据没能加载出来。</p>
            <BaseButton size="small" :loading="isRefreshing" @click="loadCurrentTab(true)">
              重试
            </BaseButton>
          </BaseSurface>
          <BaseSurface v-else padding="large" class="menu-assignment-layout__content">
            <p class="system-page__empty">暂无可管理的角色。</p>
          </BaseSurface>
        </div>
      </template>
    </div>
  </main>
</template>

<style scoped>
.menu-tabs {
  display: flex;
  gap: var(--sys-space-2);
  margin-block-end: var(--sys-space-5);
  border-block-end: 1px solid var(--sys-color-border);
}

.menu-tabs__item {
  display: grid;
  gap: 0.2rem;
  border: 0;
  border-block-end: 2px solid transparent;
  padding: var(--sys-space-3) var(--sys-space-4);
  background: transparent;
  color: var(--sys-color-text-muted);
  cursor: pointer;
  text-align: start;
  font: var(--sys-typography-body-compact);
}

.menu-tabs__item:hover,
.menu-tabs__item--active {
  border-block-end-color: var(--sys-color-action-primary);
  color: var(--sys-color-action-primary);
}

.menu-tabs__item small {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

/* ---------- 菜单结构：左树 + 右详情 ---------- */

.menu-structure {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(21rem, 26rem);
  align-items: start;
  gap: var(--sys-space-5);
}

.menu-structure__tree {
  min-inline-size: 0;
}

.menu-structure__toolbar {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--sys-space-4);
  border-block-end: 1px solid var(--sys-color-border);
  padding: var(--sys-space-5);
}

/*
 * 工具栏在窄栏里会挤爆：筛选框（共享的 .system-page__filter）用的是
 * inline-size: min(100%, 28rem)，作为 flex 项时下不去，右侧「新建菜单」就被顶出卡片。
 * 让它可收缩，输入框跟着一起收。
 */
.menu-structure__toolbar .system-page__filter {
  flex: 1 1 auto;
  inline-size: auto;
  max-inline-size: 28rem;
  min-inline-size: 0;
}

.menu-structure__toolbar .system-page__filter input {
  inline-size: 100%;
  min-inline-size: 0;
}

.menu-structure__toolbar-meta {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--sys-space-4);
}

.menu-structure__hint {
  margin: 0;
  border-block-end: 1px solid var(--sys-color-border);
  padding: var(--sys-space-3) var(--sys-space-5);
  background: var(--sys-color-surface-muted);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.menu-tree {
  padding: var(--sys-space-3);
}

/*
 * 窄屏时树行改为「父容器内部横向滚动」。
 *
 * 和权限管理页的处理**不一样**，因为要的效果不同：
 *   · 权限管理页那一行是「固定宽的资源名 + 可滚的操作区」——操作区本来就该变窄并自己滚，
 *     所以那边是让网格项能收缩（min-inline-size: 0），行高不变；
 *   · 菜单树这一行是**整体**放不下（把手 + 箭头 + 图标 + 名称/路由 + 标签 + 一排按钮），
 *     没有哪一段该被压扁。所以让它保持自然宽度、由 .menu-tree 滚动，名称不再被省略号截断。
 *
 * min-inline-size: max-content 是这里的要点：flex 行默认会被父级压到容器宽度，
 * 那样不会溢出、也就没有东西可滚，名称反而被 ellipsis 吃掉。
 * 滚动容器是 .menu-tree（也就是卡片内部），所以横滑不会顶破卡片或整页。
 */
@media (max-width: 48rem) {
  .menu-tree {
    overflow-x: auto;
  }

  .menu-tree__row {
    min-inline-size: max-content;
  }
}

.menu-tree__row {
  position: relative;
  display: flex;
  min-block-size: 3.25rem;
  align-items: center;
  gap: var(--sys-space-2);
  border: 1px solid transparent;
  border-radius: var(--ref-radius-medium);
  padding-block: var(--sys-space-2);
  padding-inline: var(--sys-space-2);
  padding-inline-start: calc(var(--sys-space-2) + var(--tree-depth, 0) * var(--sys-space-5));
  cursor: pointer;
  transition:
    background var(--sys-motion-fast),
    border-color var(--sys-motion-fast);
}

.menu-tree__row:hover {
  background: var(--sys-color-surface-muted);
}

.menu-tree__row--selected {
  border-color: color-mix(in srgb, var(--sys-color-action-primary) 24%, var(--sys-color-border));
  background: var(--sys-color-action-subtle-hover);
}

.menu-tree__row--dragging {
  opacity: 0.45;
}

.menu-tree__row--drop-before::after,
.menu-tree__row--drop-after::after {
  content: '';
  position: absolute;
  inset-inline: var(--sys-space-2);
  block-size: 2px;
  border-radius: 2px;
  background: var(--sys-color-action-primary);
}

.menu-tree__row--drop-before::after {
  inset-block-start: -1px;
}

.menu-tree__row--drop-after::after {
  inset-block-end: -1px;
}

.menu-tree__row--drop-inside {
  border-color: var(--sys-color-action-primary);
  border-style: dashed;
  background: var(--sys-color-action-subtle-hover);
}

.menu-tree__handle {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  color: var(--sys-color-text-subtle);
  cursor: grab;
}

.menu-tree__row:active .menu-tree__handle {
  cursor: grabbing;
}

.menu-tree__twisty {
  display: grid;
  inline-size: 1.5rem;
  block-size: 1.5rem;
  flex: 0 0 auto;
  place-items: center;
  border: 0;
  border-radius: var(--ref-radius-small);
  background: transparent;
  color: var(--sys-color-text-muted);
  cursor: pointer;
}

.menu-tree__twisty:hover {
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-action-primary);
}

.menu-tree__twisty--leaf {
  cursor: default;
}

.menu-tree__icon {
  display: grid;
  inline-size: 1.9rem;
  block-size: 1.9rem;
  flex: 0 0 auto;
  place-items: center;
  border-radius: var(--ref-radius-small);
  background: var(--sys-color-surface-muted);
  color: var(--sys-color-text-accent);
}

.menu-tree__copy {
  display: grid;
  min-inline-size: 0;
  flex: 1;
  gap: 0.1rem;
}

.menu-tree__copy strong {
  overflow: hidden;
  color: var(--sys-color-text);
  font: var(--sys-typography-body-compact);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-tree__copy small {
  overflow: hidden;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-tree__match {
  background: color-mix(in srgb, var(--sys-color-action-primary) 22%, transparent);
  border-radius: 0.2rem;
}

/*
 * 「隐藏」不再等同于「停用」。原来这一行是按 enabled 变淡的 —— 但 status 删掉之后
 * 只剩下 visible，而 hidden 的菜单在角色授权里**仍然可以勾选**（它是「藏起来但保留」，
 * 不是「不能用」），所以这里只做很轻微的弱化，让它仍然像一条正常的、可操作的行。
 */
.menu-tree__row--hidden .menu-tree__copy,
.menu-tree__row--hidden .menu-tree__icon {
  opacity: 0.75;
}

.menu-tree__tags {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--sys-space-1);
}

.menu-tree__tag {
  border: 1px solid var(--sys-color-border);
  border-radius: var(--ref-radius-round);
  padding: 0.05rem 0.5rem;
  background: var(--sys-color-surface-raised);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  white-space: nowrap;
}

.menu-tree__tag--muted {
  border-style: dashed;
}

.menu-tree__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--sys-space-1);
}

.menu-tree__confirm {
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-caption);
  white-space: nowrap;
}

.menu-structure__empty {
  display: flex;
  align-items: start;
  gap: var(--sys-space-3);
  padding: var(--sys-space-6) var(--sys-space-5);
  color: var(--sys-color-text-muted);
}

.menu-structure__empty strong {
  display: block;
  margin-block-end: var(--sys-space-2);
  color: var(--sys-color-text);
}

.menu-structure__empty p {
  margin: 0 0 var(--sys-space-4);
  max-inline-size: 42rem;
  font: var(--sys-typography-caption);
}

.menu-structure__empty code {
  font-family: var(--ref-font-mono);
}

/* ---------- 详情面板 ---------- */

.menu-inspector {
  position: sticky;
  inset-block-start: var(--sys-space-5);
  min-inline-size: 0;
  border-block-start: 3px solid var(--sys-color-action-primary);
}

/*
 * 检查器里的表单：窄栏内的尺寸与节奏。
 *
 * 输入框和 select 的「自动最小尺寸」是 min-content（≈ size=20 的固有宽度，200px 上下），
 * 它会顺着 grid / flex 容器一路顶上去，把控件挤出卡片 —— 面板只有 21~26rem，
 * 一列分到 160~180px，于是「路由地址」「备注」这类字段会越过卡片右边界。
 * 只给控件本身写 inline-size: 100% 治不了：必须把这条链上每一层的 min-inline-size
 * 都置 0，控件才会真正缩到列宽以内。
 */
.menu-inspector :deep(.base-field),
.menu-inspector :deep(.base-field__label-row),
.menu-inspector :deep(.base-field__control),
.menu-inspector :deep(.base-field__message) {
  min-inline-size: 0;
}

.menu-inspector .menu-page__native-field select,
.menu-inspector .menu-page__native-field input {
  max-inline-size: 100%;
}

/*
 * BaseField 固定为提示 / 错误预留一行，本页既没有 hint 也没有逐字段 error，
 * 那一行永远是空白。留着它会让「两列 BaseField 的行」比「两列原生字段的行」高出一截，
 * 行距忽大忽小；去掉之后每行等高。
 */
.menu-inspector :deep(.base-field__message--empty) {
  display: none;
}

.menu-inspector .menu-page__form {
  gap: var(--sys-space-4);
}

.menu-inspector .menu-page__form-grid {
  gap: var(--sys-space-4);
}

/*
 * 组件注册 key 与备注占满一行：两条分支（菜单 8 个字段、目录 7 个字段）里它们的奇偶不同，
 * 让这两个长值字段通吃整行，两种形态都不会在最后一行留下空格子。
 */
.menu-inspector .menu-page__field-wide {
  grid-column: 1 / -1;
}

.menu-inspector__head {
  display: flex;
  align-items: center;
  gap: var(--sys-space-3);
  margin-block-end: var(--sys-space-5);
}

.menu-inspector__head h2 {
  margin: 0 0 0.15rem;
  font: 700 1.2rem/1.2 var(--ref-font-sans);
}

.menu-inspector__icon {
  display: grid;
  inline-size: 2.4rem;
  block-size: 2.4rem;
  flex: 0 0 auto;
  place-items: center;
  border-radius: var(--ref-radius-medium);
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-text-accent);
}

.menu-inspector__facts {
  display: grid;
  gap: var(--sys-space-2);
  margin: 0 0 var(--sys-space-5);
}

.menu-inspector__facts > div {
  display: grid;
  grid-template-columns: 6.5rem minmax(0, 1fr);
  gap: var(--sys-space-3);
  border-block-end: 1px solid var(--sys-color-border);
  padding-block-end: var(--sys-space-2);
}

.menu-inspector__facts > div:last-child {
  border-block-end: 0;
}

.menu-inspector__facts dt {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.menu-inspector__facts dd {
  min-inline-size: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--sys-color-text);
  font: var(--sys-typography-caption);
}

.menu-inspector__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sys-space-2);
}

.menu-inspector__note {
  margin: var(--sys-space-3) 0 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.menu-inspector__empty {
  display: grid;
  justify-items: start;
  gap: var(--sys-space-3);
  padding-block: var(--sys-space-5);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.menu-inspector__empty p {
  margin: 0;
}

/* ---------- 图标选择器 ---------- */

.menu-icon-picker {
  display: grid;
  gap: var(--sys-space-2);
}

.menu-icon-picker__label {
  color: var(--sys-color-text);
  font: var(--sys-typography-label-strong);
}

/*
 * 等宽网格而不是 flex-wrap：flex 换行会让每行图标数量随面板宽度变化、右边缘参差不齐，
 * auto-fill 让方格自己铺满一行，看起来才像一组可选图标。2.8rem 的下限在 26rem 面板里
 * 正好排 7 列 × 2 行，方格也大到好点。
 */
.menu-icon-picker__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(2.8rem, 1fr));
  gap: var(--sys-space-1);
}

.menu-icon-picker__option {
  display: grid;
  aspect-ratio: 1;
  place-items: center;
  border: 1px solid var(--sys-color-border-strong);
  border-radius: var(--ref-radius-small);
  background: var(--sys-color-field);
  color: var(--sys-color-text-muted);
  cursor: pointer;
}

.menu-icon-picker__option:hover {
  border-color: var(--sys-color-border-interactive);
  color: var(--sys-color-action-primary);
}

.menu-icon-picker__option--active {
  border-color: var(--sys-color-action-primary);
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-action-primary);
}

.menu-icon-picker__warning {
  display: flex;
  align-items: center;
  gap: var(--sys-space-2);
  margin: 0;
  color: var(--sys-color-warning-text);
  font: var(--sys-typography-caption);
}

/* ---------- 角色菜单 ---------- */

.menu-assignment-layout {
  display: grid;
  grid-template-columns: minmax(13rem, 0.3fr) minmax(0, 1fr);
  align-items: start;
  gap: var(--sys-space-5);
}

.menu-assignment-layout__sidebar {
  position: sticky;
  inset-block-start: var(--sys-space-5);
}

.menu-assignment-layout__content {
  min-inline-size: 0;
}

.menu-assignment__roles {
  display: grid;
  gap: var(--sys-space-2);
}

.menu-assignment__role {
  display: grid;
  gap: 0.25rem;
  border: 1px solid transparent;
  border-radius: var(--cmp-surface-radius);
  padding: var(--sys-space-3);
  background: transparent;
  color: var(--sys-color-text-muted);
  cursor: pointer;
  text-align: start;
}

.menu-assignment__role:hover,
.menu-assignment__role--active {
  border-color: color-mix(in srgb, var(--sys-color-action-primary) 25%, var(--sys-color-border));
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-action-primary);
}

.menu-assignment__role span {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.menu-assignment__hint {
  margin-block-end: var(--sys-space-4);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.menu-assignment__tree {
  display: grid;
  gap: 0.15rem;
}

.menu-assignment__row {
  display: flex;
  min-block-size: 3.1rem;
  align-items: center;
  gap: var(--sys-space-3);
  border-block-end: 1px solid var(--sys-color-border);
  padding-block: var(--sys-space-2);
}

/*
 * 展开箭头和菜单结构树里的 .menu-tree__twisty 保持一致：同样的 1.5rem 点击区、
 * 同样的 Lucide ChevronRight/ChevronDown、同样的 hover 反馈。
 *
 * 之前这里用的是文字符号 ⌄ / › —— 字形和大小取决于字体，和别的树的图标对不齐，
 * 屏读器还可能把符号本身念出来。图标更可控。
 */
.menu-assignment__expand,
.menu-assignment__expand-placeholder {
  display: grid;
  inline-size: 1.5rem;
  block-size: 1.5rem;
  flex: 0 0 auto;
  place-items: center;
}

.menu-assignment__expand {
  border: 0;
  border-radius: var(--ref-radius-small);
  background: transparent;
  color: var(--sys-color-text-muted);
  cursor: pointer;
}

.menu-assignment__expand:hover {
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-action-primary);
}

.menu-assignment__copy {
  display: grid;
  min-inline-size: 0;
  flex: 1;
  gap: 0.2rem;
}

.menu-assignment__copy strong,
.menu-assignment__copy code {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-assignment__copy code {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

/* 68rem 时左树只剩 250px 上下，菜单名和工具栏都开始挤，提前到 72rem 就让位给上下布局。 */
@media (max-width: 72rem) {
  .menu-structure,
  .menu-assignment-layout {
    grid-template-columns: 1fr;
  }

  .menu-inspector,
  .menu-assignment-layout__sidebar {
    position: static;
  }

  .menu-assignment__roles {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 48rem) {
  .menu-tabs {
    overflow-x: auto;
  }

  .menu-structure__toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .menu-assignment__roles {
    grid-template-columns: 1fr;
  }
}
</style>
