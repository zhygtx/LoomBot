<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ApiError } from '@shared/api/http-client'
import { hasPermission } from '@shared/session/permissions'
import { BaseButton, BaseNotice, BaseSurface } from '@shared/ui'

import { useSessionStore } from '../../auth/model/session-store'
import { systemApi } from '../api/system-api'
import type {
  BackendPermission,
  RolePermissionRelation,
  RoleSummary,
  UserRoleRelation,
} from '../model/types'

type AccessTab = 'role-permissions' | 'user-roles'

interface PermissionResource {
  key: string
  label: string
  items: BackendPermission[]
}

interface PermissionGroup {
  key: string
  label: string
  resources: PermissionResource[]
}

const session = useSessionStore()
const roles = ref<RolePermissionRelation[]>([])
const roleSummaries = ref<RoleSummary[]>([])
const users = ref<UserRoleRelation[]>([])
const permissions = ref<BackendPermission[]>([])
const selectedRoleId = ref<string | null>(null)
const selectedUserId = ref<string | null>(null)
const activeTab = ref<AccessTab>('role-permissions')
const permissionQuery = ref('')
const openGroups = ref<string[]>([])
const openResources = ref<string[]>([])
const isLoading = ref(true)
const isRefreshing = ref(false)
const isSaving = ref(false)
const errorMessage = ref('')
const noticeMessage = ref('')
const loadedRolePermissions = ref(false)
const loadedUserRoles = ref(false)

const canListRoles = computed(() => hasPermission(session.user?.permissions, 'system:role:list'))
const canUpdateRoles = computed(() =>
  hasPermission(session.user?.permissions, 'system:role:update'),
)
const canListUsers = computed(() => hasPermission(session.user?.permissions, 'system:user:list'))
const canUpdateUsers = computed(() =>
  hasPermission(session.user?.permissions, 'system:user:update'),
)
const canAccess = computed(() => canListRoles.value || canListUsers.value)
const selectedRole = computed(() => roles.value.find((role) => role.id === selectedRoleId.value))
const selectedUser = computed(() => users.value.find((user) => user.id === selectedUserId.value))
const selectedRolePermissionIds = computed<string[]>({
  get: () => selectedRole.value?.permissionIds ?? [],
  set: (ids) => {
    if (selectedRole.value) selectedRole.value.permissionIds = ids
  },
})
const selectedUserRoleIds = computed<string[]>({
  get: () => selectedUser.value?.roleIds ?? [],
  set: (ids) => {
    if (selectedUser.value) selectedUser.value.roleIds = ids
  },
})

const permissionGroups = computed<PermissionGroup[]>(() => {
  const query = permissionQuery.value.trim().toLowerCase()
  const groups = new Map<string, Map<string, BackendPermission[]>>()

  permissions.value
    .filter((permission) => {
      if (!query) return true
      return [permission.name, permission.permission].some((value) =>
        value.toLowerCase().includes(query),
      )
    })
    .forEach((permission) => {
      const segments = permission.permission.split(':')
      const domain = segments.length === 1 ? 'global' : segments[0] || 'other'
      const resource = segments.length <= 2 ? 'general' : segments[1] || 'general'
      const resources = groups.get(domain) ?? new Map<string, BackendPermission[]>()
      resources.set(resource, [...(resources.get(resource) ?? []), permission])
      groups.set(domain, resources)
    })

  return [...groups.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([domain, resources]) => ({
      key: domain,
      label: domain === 'global' ? '全局权限' : domain,
      resources: [...resources.entries()]
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([resource, items]) => ({
          key: `${domain}:${resource}`,
          label: resource === 'general' ? '通用操作' : resource,
          items: items.sort((left, right) => left.permission.localeCompare(right.permission)),
        })),
    }))
})

function isOpen(key: string, values: string[]): boolean {
  return values.includes(key)
}

function toggleGroup(key: string): void {
  openGroups.value = isOpen(key, openGroups.value)
    ? openGroups.value.filter((value) => value !== key)
    : [...openGroups.value, key]
}

function toggleResource(key: string): void {
  openResources.value = isOpen(key, openResources.value)
    ? openResources.value.filter((value) => value !== key)
    : [...openResources.value, key]
}

function permissionCount(items: BackendPermission[]): string {
  const selected = new Set(selectedRolePermissionIds.value)
  return `${items.filter((item) => selected.has(item.id)).length}/${items.length}`
}

function setResourceSelection(resource: PermissionResource, checked: boolean): void {
  const ids = new Set(selectedRolePermissionIds.value)
  resource.items
    .filter((item) => item.enabled)
    .forEach((item) => (checked ? ids.add(item.id) : ids.delete(item.id)))
  selectedRolePermissionIds.value = [...ids]
}

function setGroupSelection(group: PermissionGroup, checked: boolean): void {
  group.resources.forEach((resource) => setResourceSelection(resource, checked))
}

function groupSelected(group: PermissionGroup): boolean {
  const items = group.resources.flatMap((resource) => resource.items).filter((item) => item.enabled)
  return (
    items.length > 0 && items.every((item) => selectedRolePermissionIds.value.includes(item.id))
  )
}

function resourceSelected(resource: PermissionResource): boolean {
  const items = resource.items.filter((item) => item.enabled)
  return (
    items.length > 0 && items.every((item) => selectedRolePermissionIds.value.includes(item.id))
  )
}

function setInitialSelection(): void {
  if (!selectedRoleId.value) selectedRoleId.value = roles.value[0]?.id ?? null
  if (!selectedUserId.value) selectedUserId.value = users.value[0]?.id ?? null
  if (openGroups.value.length === 0)
    openGroups.value = permissionGroups.value.map((group) => group.key)
  if (openResources.value.length === 0)
    openResources.value = permissionGroups.value.flatMap((group) =>
      group.resources.map((resource) => resource.key),
    )
}

async function loadRolePermissions(): Promise<void> {
  if (!canListRoles.value || loadedRolePermissions.value) return
  const [nextRoles, nextPermissions] = await Promise.all([
    systemApi.listRolePermissions(),
    systemApi.listPermissions(),
  ])
  roles.value = nextRoles
  permissions.value = nextPermissions
  loadedRolePermissions.value = true
  setInitialSelection()
}

async function loadUserRoles(): Promise<void> {
  if (!canListUsers.value || loadedUserRoles.value) return
  const [nextRoles, nextUsers] = await Promise.all([
    systemApi.listRoleSummaries(),
    systemApi.listUserRoles(),
  ])
  roleSummaries.value = nextRoles
  users.value = nextUsers
  loadedUserRoles.value = true
  setInitialSelection()
}

async function loadCurrentTab(force = false): Promise<void> {
  isRefreshing.value = true
  errorMessage.value = ''
  noticeMessage.value = ''
  try {
    if (!session.user) await session.loadProfile()
    if (force) {
      if (activeTab.value === 'role-permissions') loadedRolePermissions.value = false
      else loadedUserRoles.value = false
    }
    if (activeTab.value === 'role-permissions') await loadRolePermissions()
    else await loadUserRoles()
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '权限数据加载失败，请稍后重试'
  } finally {
    isLoading.value = false
    isRefreshing.value = false
  }
}

async function switchTab(tab: AccessTab): Promise<void> {
  if (activeTab.value === tab) return
  activeTab.value = tab
  isLoading.value = true
  await loadCurrentTab()
}

async function saveRolePermissions(): Promise<void> {
  if (!selectedRole.value || !canUpdateRoles.value) return
  isSaving.value = true
  errorMessage.value = ''
  noticeMessage.value = ''
  try {
    await systemApi.updateRolePermissions(selectedRole.value.id, selectedRolePermissionIds.value)
    noticeMessage.value = '角色权限已保存'
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '角色权限保存失败，请稍后重试'
  } finally {
    isSaving.value = false
  }
}

async function saveUserRoles(): Promise<void> {
  if (!selectedUser.value || !canUpdateUsers.value) return
  isSaving.value = true
  errorMessage.value = ''
  noticeMessage.value = ''
  try {
    await systemApi.updateUserRoles(selectedUser.value.id, selectedUserRoleIds.value)
    noticeMessage.value = '用户角色已保存'
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '用户角色保存失败，请稍后重试'
  } finally {
    isSaving.value = false
  }
}

onMounted(async () => {
  if (!session.user) await session.loadProfile()
  if (!canListRoles.value && canListUsers.value) activeTab.value = 'user-roles'
  await loadCurrentTab()
})
</script>

<template>
  <main class="system-page access-page">
    <div class="system-page__shell">
      <header class="system-page__header">
        <div>
          <span class="system-page__eyebrow">System / Access</span>
          <h1>权限管理</h1>
          <p>管理用户与角色、角色与操作权限。菜单结构和角色菜单分配请前往菜单管理。</p>
        </div>
        <BaseButton
          appearance="secondary"
          size="small"
          :loading="isRefreshing"
          @click="loadCurrentTab(true)"
        >
          刷新
        </BaseButton>
      </header>

      <BaseNotice v-if="errorMessage" tone="danger">{{ errorMessage }}</BaseNotice>
      <BaseNotice v-if="noticeMessage" tone="success">{{ noticeMessage }}</BaseNotice>

      <nav v-if="canAccess" class="access-tabs" aria-label="权限管理分区">
        <button
          v-if="canListRoles"
          type="button"
          class="access-tabs__item"
          :class="{ 'access-tabs__item--active': activeTab === 'role-permissions' }"
          @click="switchTab('role-permissions')"
        >
          角色权限
          <small>控制按钮与接口操作</small>
        </button>
        <button
          v-if="canListUsers"
          type="button"
          class="access-tabs__item"
          :class="{ 'access-tabs__item--active': activeTab === 'user-roles' }"
          @click="switchTab('user-roles')"
        >
          用户角色
          <small>把用户加入角色</small>
        </button>
      </nav>

      <BaseSurface v-if="isLoading" padding="large">
        <p class="system-page__empty">正在读取权限数据…</p>
      </BaseSurface>
      <BaseNotice v-else-if="!canAccess" tone="warning" title="没有权限">
        当前用户没有查看权限关系的权限。
      </BaseNotice>

      <template v-else-if="activeTab === 'role-permissions' && canListRoles">
        <div class="access-layout">
          <BaseSurface padding="medium" class="access-layout__sidebar">
            <div class="access-panel-heading">
              <div>
                <span>Roles</span>
                <h2>角色</h2>
              </div>
              <span class="access-panel-heading__count">{{ roles.length }}</span>
            </div>
            <div class="access-role-list">
              <button
                v-for="role in roles"
                :key="role.id"
                type="button"
                class="access-role"
                :class="{ 'access-role--active': role.id === selectedRoleId }"
                @click="selectedRoleId = role.id"
              >
                <strong>{{ role.name }}</strong>
                <span>{{ role.code }}</span>
              </button>
            </div>
          </BaseSurface>

          <BaseSurface v-if="selectedRole" padding="medium" class="access-layout__content">
            <div class="access-panel-heading access-panel-heading--content">
              <div>
                <span>Role permissions</span>
                <h2>{{ selectedRole.name }} · 操作权限</h2>
              </div>
              <BaseButton
                v-if="canUpdateRoles"
                size="small"
                :loading="isSaving"
                @click="saveRolePermissions"
              >
                保存权限
              </BaseButton>
            </div>

            <div class="permission-toolbar">
              <label class="system-page__filter">
                <span>筛选权限</span>
                <input v-model="permissionQuery" type="search" placeholder="搜索名称或权限字符串" />
              </label>
              <span class="system-page__count"
                >{{ selectedRolePermissionIds.length }} 项已选择</span
              >
            </div>

            <div class="permission-groups">
              <article v-for="group in permissionGroups" :key="group.key" class="permission-group">
                <div class="permission-group__heading">
                  <button
                    type="button"
                    class="permission-group__toggle"
                    @click="toggleGroup(group.key)"
                  >
                    <span
                      class="permission-group__chevron"
                      :class="{ 'permission-group__chevron--open': isOpen(group.key, openGroups) }"
                      >⌄</span
                    >
                    <strong>{{ group.label }}</strong>
                    <small
                      >{{ group.resources.flatMap((resource) => resource.items).length }} 项</small
                    >
                  </button>
                  <button
                    v-if="canUpdateRoles"
                    type="button"
                    class="permission-group__select"
                    @click="setGroupSelection(group, !groupSelected(group))"
                  >
                    {{ groupSelected(group) ? '取消全选' : '全选' }}
                  </button>
                </div>
                <div v-if="isOpen(group.key, openGroups)" class="permission-group__body">
                  <section
                    v-for="resource in group.resources"
                    :key="resource.key"
                    class="permission-resource"
                  >
                    <div class="permission-resource__heading">
                      <button
                        type="button"
                        class="permission-resource__toggle"
                        @click="toggleResource(resource.key)"
                      >
                        <span
                          class="permission-group__chevron"
                          :class="{
                            'permission-group__chevron--open': isOpen(resource.key, openResources),
                          }"
                          >⌄</span
                        >
                        <strong>{{ resource.label }}</strong>
                        <small>{{ permissionCount(resource.items) }}</small>
                      </button>
                      <button
                        v-if="canUpdateRoles"
                        type="button"
                        class="permission-group__select"
                        @click="setResourceSelection(resource, !resourceSelected(resource))"
                      >
                        {{ resourceSelected(resource) ? '取消全选' : '全选' }}
                      </button>
                    </div>
                    <div
                      v-if="isOpen(resource.key, openResources)"
                      class="permission-resource__items"
                    >
                      <label
                        v-for="permission in resource.items"
                        :key="permission.id"
                        class="permission-row"
                      >
                        <input
                          v-model="selectedRolePermissionIds"
                          type="checkbox"
                          :value="permission.id"
                          :disabled="!canUpdateRoles || !permission.enabled"
                        />
                        <span>
                          <strong>{{ permission.name }}</strong>
                          <code>{{ permission.permission }}</code>
                        </span>
                        <em v-if="!permission.enabled">已停用</em>
                      </label>
                    </div>
                  </section>
                </div>
              </article>
              <p v-if="!permissionGroups.length" class="system-page__empty">没有匹配的权限。</p>
            </div>
          </BaseSurface>
          <BaseSurface v-else padding="large" class="access-layout__content">
            <p class="system-page__empty">暂无可管理的角色。</p>
          </BaseSurface>
        </div>
      </template>

      <template v-else-if="activeTab === 'user-roles' && canListUsers">
        <BaseSurface padding="medium" class="user-role-panel">
          <div class="access-panel-heading access-panel-heading--content">
            <div>
              <span>User roles</span>
              <h2>用户角色</h2>
            </div>
            <BaseButton
              v-if="canUpdateUsers"
              size="small"
              :loading="isSaving"
              @click="saveUserRoles"
            >
              保存角色
            </BaseButton>
          </div>
          <label class="permission-user-select">
            <span>选择用户</span>
            <select v-model="selectedUserId">
              <option v-for="user in users" :key="user.id" :value="user.id">
                {{ user.email }}
              </option>
            </select>
          </label>
          <div v-if="selectedUser" class="user-role-grid">
            <label
              v-for="role in roleSummaries"
              :key="role.id"
              class="permission-row user-role-row"
            >
              <input
                v-model="selectedUserRoleIds"
                type="checkbox"
                :value="role.id"
                :disabled="!canUpdateUsers || !role.enabled"
              />
              <span>
                <strong>{{ role.name }}</strong>
                <code>{{ role.code }}</code>
              </span>
              <em v-if="!role.enabled">已停用</em>
            </label>
          </div>
          <p v-else class="system-page__empty">暂无可管理的用户。</p>
        </BaseSurface>
      </template>
    </div>
  </main>
</template>

<style scoped>
.access-tabs {
  display: flex;
  gap: var(--sys-space-2);
  margin-block-end: var(--sys-space-5);
  border-block-end: 1px solid var(--sys-color-border);
}

.access-tabs__item {
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

.access-tabs__item:hover,
.access-tabs__item--active {
  border-block-end-color: var(--sys-color-action-primary);
  color: var(--sys-color-action-primary);
}

.access-tabs__item small {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.access-layout {
  display: grid;
  grid-template-columns: minmax(13rem, 0.3fr) minmax(0, 1fr);
  align-items: start;
  gap: var(--sys-space-5);
}

.access-layout__sidebar {
  position: sticky;
  inset-block-start: var(--sys-space-5);
}

.access-layout__content {
  min-inline-size: 0;
}

.access-panel-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--sys-space-4);
  margin-block-end: var(--sys-space-5);
}

.access-panel-heading > div {
  display: grid;
  gap: var(--sys-space-2);
}

.access-panel-heading span {
  color: var(--sys-color-text-accent);
  font: var(--sys-typography-label);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.access-panel-heading h2 {
  margin: 0;
  font: 700 1.2rem/1.2 var(--ref-font-sans);
}

.access-panel-heading__count,
.access-panel-heading--content > div > span {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  letter-spacing: 0;
  text-transform: none;
}

.access-role-list {
  display: grid;
  gap: var(--sys-space-2);
}

.access-role {
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

.access-role:hover,
.access-role--active {
  border-color: color-mix(in srgb, var(--sys-color-action-primary) 25%, var(--sys-color-border));
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-action-primary);
}

.access-role span,
.permission-row code {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.permission-toolbar {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--sys-space-4);
  margin-block-end: var(--sys-space-5);
}

.permission-groups {
  display: grid;
  gap: var(--sys-space-3);
}

.permission-group,
.permission-resource {
  border: 1px solid var(--sys-color-border);
  border-radius: var(--cmp-surface-radius);
  background: var(--sys-color-surface-raised);
}

.permission-group__heading,
.permission-resource__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sys-space-3);
  padding: var(--sys-space-3) var(--sys-space-4);
}

.permission-group__toggle,
.permission-resource__toggle {
  display: flex;
  min-inline-size: 0;
  align-items: center;
  gap: var(--sys-space-2);
  border: 0;
  background: transparent;
  color: var(--sys-color-text);
  cursor: pointer;
  text-align: start;
}

.permission-group__toggle strong {
  font: 700 1rem/1.2 var(--ref-font-sans);
}

.permission-resource__toggle strong {
  font: var(--sys-typography-body-compact);
}

.permission-group__toggle small,
.permission-resource__toggle small {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.permission-group__chevron {
  display: inline-block;
  color: var(--sys-color-text-muted);
  transform: rotate(-90deg);
  transition: transform var(--sys-motion-fast);
}

.permission-group__chevron--open {
  transform: rotate(0);
}

.permission-group__select {
  flex: 0 0 auto;
  border: 0;
  background: transparent;
  color: var(--sys-color-action-primary);
  cursor: pointer;
  font: var(--sys-typography-caption);
}

.permission-group__body {
  display: grid;
  gap: var(--sys-space-3);
  border-block-start: 1px solid var(--sys-color-border);
  padding: var(--sys-space-3);
}

.permission-resource__heading {
  padding-block: var(--sys-space-2);
}

.permission-resource__items {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sys-space-2);
  padding: 0 var(--sys-space-3) var(--sys-space-3);
}

.permission-row {
  display: flex;
  min-inline-size: 0;
  align-items: start;
  gap: var(--sys-space-3);
  border: 1px solid var(--sys-color-border);
  border-radius: var(--cmp-surface-radius);
  padding: var(--sys-space-3);
}

.permission-row > span {
  display: grid;
  min-inline-size: 0;
  flex: 1;
  gap: 0.25rem;
}

.permission-row strong,
.permission-row code {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.permission-row em {
  flex: 0 0 auto;
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-caption);
  font-style: normal;
}

.user-role-panel {
  max-inline-size: 62rem;
}

.user-role-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sys-space-3);
  margin-block-start: var(--sys-space-5);
}

@media (max-width: 68rem) {
  .access-layout {
    grid-template-columns: 1fr;
  }

  .access-layout__sidebar {
    position: static;
  }

  .access-role-list {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 48rem) {
  .access-tabs {
    overflow-x: auto;
  }

  .permission-toolbar,
  .access-panel-heading {
    align-items: stretch;
    flex-direction: column;
  }

  .permission-resource__items,
  .user-role-grid,
  .access-role-list {
    grid-template-columns: 1fr;
  }
}
</style>
