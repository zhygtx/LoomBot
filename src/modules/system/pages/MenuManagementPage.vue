<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { ApiError } from '@shared/api/http-client'
import { hasPermission } from '@shared/session/permissions'
import { BaseButton, BaseField, BaseNotice, BaseSurface } from '@shared/ui'

import { useSessionStore } from '../../auth/model/session-store'
import { systemApi } from '../api/system-api'
import type { MenuItem, MenuPayload, RoleMenuRelation } from '../model/types'

type MenuTab = 'structure' | 'assignment'

interface MenuNode extends MenuItem {
  children: MenuNode[]
}

interface MenuRow {
  node: MenuNode
  depth: number
}

const session = useSessionStore()
const menus = ref<MenuItem[]>([])
const assignmentMenus = ref<MenuItem[]>([])
const roleMenus = ref<RoleMenuRelation[]>([])
const activeTab = ref<MenuTab>('structure')
const selectedRoleId = ref<string | null>(null)
const openMenuGroups = ref<string[]>([])
const query = ref('')
const isLoading = ref(true)
const isRefreshing = ref(false)
const isSaving = ref(false)
const deletingId = ref<string | null>(null)
const actionId = ref<string | null>(null)
const errorMessage = ref('')
const noticeMessage = ref('')
const editingId = ref<string | null>(null)
const form = reactive<MenuPayload>(emptyPayload())

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
const selectedRole = computed(() =>
  roleMenus.value.find((role) => role.id === selectedRoleId.value),
)
const selectedRoleMenuIds = computed<string[]>({
  get: () => selectedRole.value?.menuIds ?? [],
  set: (ids) => {
    if (selectedRole.value) selectedRole.value.menuIds = ids
  },
})
const menuTree = computed(() =>
  buildTree(assignmentMenus.value.length ? assignmentMenus.value : menus.value),
)
const menuRows = computed(() => flattenTree(menuTree.value))
const loadedStructure = ref(false)
const loadedAssignment = ref(false)
const filteredMenus = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return menus.value
  return menus.value.filter((item) =>
    [item.name, item.routeName ?? '', item.path ?? '', item.componentKey ?? ''].some((value) =>
      value.toLowerCase().includes(keyword),
    ),
  )
})

function invalidateAssignmentCache(): void {
  loadedAssignment.value = false
  assignmentMenus.value = []
}

function emptyPayload(): MenuPayload {
  return {
    parentId: '0',
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
  editingId.value = null
}

function editMenu(menu: MenuItem): void {
  editingId.value = menu.id
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
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function parentLabel(parentId: string): string {
  return parentId === '0'
    ? '顶级菜单'
    : (menus.value.find((item) => item.id === parentId)?.name ?? '未知父级')
}

function formatStatus(menu: MenuItem): string {
  return menu.enabled ? '已启用' : '已停用'
}

function buildTree(items: MenuItem[]): MenuNode[] {
  const nodes = new Map<string, MenuNode>()
  items.forEach((item) => nodes.set(item.id, { ...item, children: [] }))
  const roots: MenuNode[] = []
  nodes.forEach((node) => {
    const parent = node.parentId === '0' ? undefined : nodes.get(node.parentId)
    if (parent) parent.children.push(node)
    else roots.push(node)
  })
  const sortNodes = (entries: MenuNode[]) => {
    entries.sort(
      (left, right) => left.sort - right.sort || left.name.localeCompare(right.name, 'zh-CN'),
    )
    entries.forEach((entry) => sortNodes(entry.children))
  }
  sortNodes(roots)
  return roots
}

function flattenTree(nodes: MenuNode[], depth = 0): MenuRow[] {
  const result: MenuRow[] = []
  nodes.forEach((node) => {
    result.push({ node, depth })
    if (node.children.length && openMenuGroups.value.includes(node.id))
      result.push(...flattenTree(node.children, depth + 1))
  })
  return result
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
  if (!menu || menu.parentId === '0') return
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

function setAssignmentSelection(): void {
  if (!selectedRoleId.value) selectedRoleId.value = roleMenus.value[0]?.id ?? null
  if (openMenuGroups.value.length === 0)
    openMenuGroups.value = menuTree.value
      .filter((menu) => menu.children.length)
      .map((menu) => menu.id)
}

async function loadStructure(): Promise<void> {
  if (!canList.value || loadedStructure.value) return
  errorMessage.value = ''
  isRefreshing.value = true
  try {
    menus.value = await systemApi.listMenus()
    loadedStructure.value = true
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '菜单数据加载失败，请稍后重试'
  } finally {
    isLoading.value = false
    isRefreshing.value = false
  }
}

async function loadAssignment(): Promise<void> {
  if (!canListRoleMenus.value || loadedAssignment.value) return
  errorMessage.value = ''
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
    setAssignmentSelection()
  } catch (error) {
    errorMessage.value =
      error instanceof ApiError ? error.message : '角色菜单数据加载失败，请稍后重试'
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
    errorMessage.value = error instanceof ApiError ? error.message : '菜单数据加载失败，请稍后重试'
    isLoading.value = false
    isRefreshing.value = false
  }
}

async function saveMenu(): Promise<void> {
  if (
    (editingId.value === null && !canCreate.value) ||
    (editingId.value !== null && !canUpdate.value)
  )
    return
  errorMessage.value = ''
  noticeMessage.value = ''
  isSaving.value = true
  try {
    const saved =
      editingId.value === null
        ? await systemApi.createMenu({ ...form })
        : await systemApi.updateMenu(editingId.value, { ...form })
    if (editingId.value === null) menus.value.push(saved)
    else {
      const index = menus.value.findIndex((item) => item.id === saved.id)
      if (index >= 0) menus.value[index] = saved
    }
    invalidateAssignmentCache()
    noticeMessage.value = editingId.value === null ? '菜单已创建' : '菜单已更新'
    resetForm()
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '菜单保存失败，请稍后重试'
  } finally {
    isSaving.value = false
  }
}

async function removeMenu(menu: MenuItem): Promise<void> {
  if (!canDelete.value || deletingId.value !== null) return
  if (!window.confirm('确定删除“' + menu.name + '”吗？')) return
  deletingId.value = menu.id
  errorMessage.value = ''
  try {
    await systemApi.deleteMenu(menu.id)
    menus.value = menus.value.filter((item) => item.id !== menu.id)
    invalidateAssignmentCache()
    if (editingId.value === menu.id) resetForm()
    noticeMessage.value = '菜单已删除'
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '菜单删除失败，请稍后重试'
  } finally {
    deletingId.value = null
  }
}

async function toggleMenu(menu: MenuItem): Promise<void> {
  if (!canUpdate.value || actionId.value !== null) return
  actionId.value = menu.id
  errorMessage.value = ''
  try {
    await systemApi.updateMenuStatus(menu.id, !menu.enabled)
    menu.enabled = !menu.enabled
    invalidateAssignmentCache()
    noticeMessage.value = '菜单状态已更新'
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '菜单状态更新失败，请稍后重试'
  } finally {
    actionId.value = null
  }
}

async function saveRoleMenus(): Promise<void> {
  if (!selectedRole.value || !canUpdateRoleMenus.value) return
  isSaving.value = true
  errorMessage.value = ''
  noticeMessage.value = ''
  try {
    await systemApi.updateRoleMenus(selectedRole.value.id, selectedRoleMenuIds.value)
    noticeMessage.value = '角色菜单已保存'
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '角色菜单保存失败，请稍后重试'
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
    errorMessage.value = error instanceof ApiError ? error.message : '菜单数据加载失败，请稍后重试'
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

      <BaseNotice v-if="errorMessage" tone="danger">{{ errorMessage }}</BaseNotice>
      <BaseNotice v-if="noticeMessage" tone="success">{{ noticeMessage }}</BaseNotice>

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
        <div class="menu-page__layout">
          <BaseSurface v-if="canCreate || canUpdate" padding="medium" class="menu-page__editor">
            <div class="menu-page__section-heading">
              <div>
                <span class="menu-page__section-kicker">{{
                  editingId ? '编辑节点' : '新增节点'
                }}</span>
                <h2>{{ editingId ? '修改菜单' : '创建菜单' }}</h2>
              </div>
              <button
                v-if="editingId"
                class="menu-page__text-button"
                type="button"
                @click="resetForm"
              >
                取消编辑
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
                <BaseField
                  v-model="form.routeName"
                  label="路由名称"
                  name="menu-route-name"
                  placeholder="system-permissions"
                />
                <BaseField
                  v-model="form.path"
                  label="路由地址"
                  name="menu-path"
                  placeholder="/system/permissions"
                />
                <BaseField
                  v-model="form.componentKey"
                  label="组件注册 key"
                  name="menu-component"
                  placeholder="system.permissions"
                />
                <BaseField
                  v-model="form.iconKey"
                  label="图标 key"
                  name="menu-icon"
                  placeholder="shield / layout / settings"
                  list="menu-icon-keys"
                />
                <datalist id="menu-icon-keys">
                  <option value="shield" />
                  <option value="layout" />
                  <option value="settings" />
                  <option value="network" />
                  <option value="key" />
                  <option value="user" />
                </datalist>
                <BaseField
                  v-model="form.redirect"
                  label="默认跳转"
                  name="menu-redirect"
                  placeholder="可选"
                />
                <BaseField
                  v-model="form.remark"
                  label="备注"
                  name="menu-remark"
                  placeholder="可选说明"
                />
                <label class="menu-page__native-field">
                  <span>父级菜单</span>
                  <select v-model="form.parentId">
                    <option value="0">顶级菜单</option>
                    <option
                      v-for="menu in menus.filter((item) => item.id !== editingId)"
                      :key="menu.id"
                      :value="menu.id"
                    >
                      {{ menu.name }}
                    </option>
                  </select>
                </label>
                <label class="menu-page__native-field">
                  <span>菜单类型</span>
                  <select v-model="form.type">
                    <option value="MENU">菜单</option>
                    <option value="CATALOG">目录</option>
                  </select>
                </label>
                <label class="menu-page__native-field">
                  <span>排序</span>
                  <input v-model.number="form.sort" type="number" min="0" max="9999" />
                </label>
              </div>
              <div class="menu-page__checks">
                <label><input v-model="form.visible" type="checkbox" /> 显示菜单</label>
                <label><input v-model="form.keepAlive" type="checkbox" /> 缓存页面</label>
              </div>
              <BaseButton
                type="submit"
                :loading="isSaving"
                :disabled="editingId === null ? !canCreate : !canUpdate"
              >
                {{ editingId ? '保存修改' : '创建菜单' }}
              </BaseButton>
            </form>
          </BaseSurface>

          <BaseSurface padding="medium" class="menu-page__list">
            <div class="system-page__toolbar">
              <div class="system-page__filter">
                <label for="menu-search">筛选菜单</label>
                <input
                  id="menu-search"
                  v-model="query"
                  type="search"
                  placeholder="按名称、路由或组件搜索"
                />
              </div>
              <span class="system-page__count"
                >显示 {{ filteredMenus.length }} / {{ menus.length }} 项</span
              >
            </div>

            <div class="menu-page__tree">
              <article v-for="menu in filteredMenus" :key="menu.id" class="menu-page__item">
                <div
                  class="menu-page__item-main"
                  :class="{ 'menu-page__item-main--child': menu.parentId !== '0' }"
                >
                  <span class="menu-page__item-marker" aria-hidden="true">{{
                    menu.type === 'CATALOG' ? '◆' : '•'
                  }}</span>
                  <div class="menu-page__item-copy">
                    <strong>{{ menu.name }}</strong>
                    <span>{{ menu.routeName || menu.path || '目录节点' }}</span>
                  </div>
                </div>
                <div class="menu-page__item-meta">
                  <span
                    class="system-page__status"
                    :class="
                      menu.enabled
                        ? 'system-page__status--enabled'
                        : 'system-page__status--disabled'
                    "
                  >
                    {{ formatStatus(menu) }}
                  </span>
                  <span class="menu-page__parent">{{ parentLabel(menu.parentId) }}</span>
                </div>
                <div class="menu-page__item-actions">
                  <button v-if="canUpdate" type="button" @click="editMenu(menu)">编辑</button>
                  <button v-if="canUpdate" type="button" @click="toggleMenu(menu)">
                    {{ menu.enabled ? '停用' : '启用' }}
                  </button>
                  <button
                    v-if="canDelete"
                    type="button"
                    :disabled="deletingId === menu.id"
                    @click="removeMenu(menu)"
                  >
                    删除
                  </button>
                </div>
              </article>
              <p v-if="!filteredMenus.length" class="system-page__empty">暂无菜单数据。</p>
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
                v-if="canUpdateRoleMenus"
                size="small"
                :loading="isSaving"
                @click="saveRoleMenus"
              >
                保存菜单
              </BaseButton>
            </div>
            <div class="menu-assignment__hint">
              勾选页面或目录。选择子菜单时，保存会自动保留它所需的父级目录。
            </div>
            <div class="menu-assignment__tree">
              <div
                v-for="row in menuRows"
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
                  {{ isMenuOpen(row.node) ? '⌄' : '›' }}
                </button>
                <span v-else class="menu-assignment__expand-placeholder" />
                <input
                  type="checkbox"
                  :checked="isMenuSelected(row.node.id)"
                  :disabled="!canUpdateRoleMenus || !row.node.enabled"
                  @change="handleMenuChange(row.node, $event)"
                />
                <span class="menu-assignment__copy">
                  <strong>{{ row.node.name }}</strong>
                  <code>{{ row.node.path || '目录节点' }}</code>
                </span>
                <span v-if="!row.node.enabled" class="menu-assignment__disabled">已停用</span>
              </div>
              <p v-if="!menuRows.length" class="system-page__empty">暂无可分配菜单。</p>
            </div>
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

.menu-assignment__expand,
.menu-assignment__expand-placeholder {
  display: grid;
  inline-size: 1.4rem;
  block-size: 1.4rem;
  flex: 0 0 auto;
  place-items: center;
}

.menu-assignment__expand {
  border: 0;
  background: transparent;
  color: var(--sys-color-text-muted);
  cursor: pointer;
  font-size: 1.1rem;
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

.menu-assignment__disabled {
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-caption);
}

@media (max-width: 68rem) {
  .menu-assignment-layout {
    grid-template-columns: 1fr;
  }

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

  .menu-assignment__roles {
    grid-template-columns: 1fr;
  }
}
</style>
