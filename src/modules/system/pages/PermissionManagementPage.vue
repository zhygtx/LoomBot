<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { ChevronDown, ChevronRight } from '@lucide/vue'

import { ApiError } from '@shared/api/http-client'
import { hasPermission } from '@shared/session/permissions'
import { BaseButton, BaseNotice, BaseSurface, message } from '@shared/ui'

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
  items: PermissionOperation[]
}

interface PermissionGroup {
  key: string
  resources: PermissionResource[]
}

interface PermissionOperation extends BackendPermission {
  key: string
  label: string
  description: string
}

const OPERATION_LABELS: Record<string, string> = {
  list: '查看',
  read: '读取',
  view: '查看',
  create: '创建',
  update: '编辑',
  delete: '删除',
  manage: '管理',
  execute: '执行',
  operate: '操作',
  export: '导出',
  upload: '上传',
}

const PERMISSION_COPY: Record<string, { label: string; description: string }> = {
  'connection:ws:create': { label: '创建', description: '允许创建 WebSocket 连接' },
  'connection:ws:delete': { label: '删除', description: '允许删除 WebSocket 连接' },
  'connection:ws:list': { label: '查看', description: '允许查看 WebSocket 连接列表' },
  'connection:ws:read': { label: '读取', description: '允许读取 WebSocket 连接详情' },
  'connection:ws:update': { label: '编辑', description: '允许更新 WebSocket 连接' },
  'connection:ws:operate': { label: '操作', description: '允许操作 WebSocket 连接' },
  'system:config:list': { label: '查看', description: '允许查看系统配置列表' },
  'system:config:update': { label: '编辑', description: '允许更新系统配置' },
  'system:permission:list': { label: '查看', description: '允许查看权限目录' },
  'system:permission:update': { label: '编辑', description: '允许更新权限状态' },
  'system:role:list': { label: '查看', description: '允许查看角色授权' },
  'system:role:update': { label: '编辑', description: '允许更新角色授权' },
  'system:user:list': { label: '查看', description: '允许查看用户角色' },
  'system:user:update': { label: '编辑', description: '允许更新用户角色' },
  'system:menu:list': { label: '查看', description: '允许查看菜单' },
  'system:menu:create': { label: '创建', description: '允许创建菜单' },
  'system:menu:update': { label: '编辑', description: '允许更新菜单' },
  'system:menu:delete': { label: '删除', description: '允许删除菜单' },
  'user:account:list': { label: '查看', description: '允许查看用户账户列表' },
  'user:account:manage': { label: '管理', description: '允许管理用户账户信息' },
  'workflow:task:view': { label: '查看', description: '允许查看工作流任务' },
  'workflow:task:execute': { label: '执行', description: '允许执行工作流任务' },
  'workflow:task:update': { label: '编辑', description: '允许更新工作流任务' },
  'workflow:task:delete': { label: '删除', description: '允许删除工作流任务' },
  'log:view': { label: '查看', description: '允许查看系统日志' },
  'file:upload:create': { label: '上传', description: '允许上传文件' },
}

const session = useSessionStore()
const roles = ref<RolePermissionRelation[]>([])
const roleSummaries = ref<RoleSummary[]>([])
const users = ref<UserRoleRelation[]>([])
const permissions = ref<BackendPermission[]>([])
const selectedRoleId = ref<string | null>(null)
const activeTab = ref<AccessTab>('role-permissions')
const permissionQuery = ref('')
const userQuery = ref('')
const userPage = ref(1)
const userPageSize = 20
const openGroups = ref<string[]>([])
const isLoading = ref(true)
const isRefreshing = ref(false)
const isSaving = ref(false)
const loadedRolePermissions = ref(false)
const loadedUserRoles = ref(false)
/**
 * 加载失败标记。失败提示走 Message 提示条，几秒后消失；但提示条消失之后，
 * 空列表会显示成「暂无可管理的角色」——那是一句假话。所以额外渲染一个常驻的
 * 失败态占位（含重试）。判据见决策记录 D72。
 */
const loadFailed = ref(false)
const initialUserSnapshots = ref<Record<string, { enabled: boolean; roleIds: string[] }>>({})

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
const selectedRolePermissionIds = computed<string[]>({
  get: () => selectedRole.value?.permissionIds ?? [],
  set: (ids) => {
    if (selectedRole.value) selectedRole.value.permissionIds = ids
  },
})
const filteredUsers = computed(() => {
  const query = userQuery.value.trim().toLowerCase()
  if (!query) return users.value
  return users.value.filter((user) => user.email.toLowerCase().includes(query))
})
const userPageCount = computed(() =>
  Math.max(1, Math.ceil(filteredUsers.value.length / userPageSize)),
)
const pagedUsers = computed(() => {
  const start = (userPage.value - 1) * userPageSize
  return filteredUsers.value.slice(start, start + userPageSize)
})
const changedUsers = computed(() =>
  users.value.filter((user) => {
    const snapshot = initialUserSnapshots.value[user.id]
    const before = `${snapshot?.enabled ?? user.enabled}|${[...(snapshot?.roleIds ?? [])].sort().join(',')}`
    const after = [...user.roleIds].sort().join(',')
    return before !== `${user.enabled}|${after}`
  }),
)

watch(userQuery, () => {
  userPage.value = 1
})

watch(userPageCount, (count) => {
  if (userPage.value > count) userPage.value = count
})

function splitPermission(permission: string): [string, string, string] | null {
  const segments = permission.split(':')
  return segments.length === 3 && segments.every(Boolean)
    ? (segments as [string, string, string])
    : null
}

function isConcretePermission(permission: string): boolean {
  const segments = splitPermission(permission)
  return Boolean(segments && segments.every((segment) => !segment.includes('*')))
}

function isPatternPermission(permission: string): boolean {
  const segments = splitPermission(permission)
  return Boolean(segments && segments.every((segment) => /^[A-Za-z0-9_.-]+$|^\*$/.test(segment)))
}

function globMatches(pattern: string, value: string): boolean {
  let patternIndex = 0
  let valueIndex = 0
  let lastStar = -1
  let starValueIndex = -1
  while (valueIndex < value.length) {
    if (patternIndex < pattern.length && pattern[patternIndex] === value[valueIndex]) {
      patternIndex += 1
      valueIndex += 1
    } else if (patternIndex < pattern.length && pattern[patternIndex] === '*') {
      lastStar = patternIndex++
      starValueIndex = valueIndex
    } else if (lastStar >= 0) {
      patternIndex = lastStar + 1
      valueIndex = ++starValueIndex
    } else return false
  }
  while (patternIndex < pattern.length && pattern[patternIndex] === '*') patternIndex += 1
  return patternIndex === pattern.length
}

function operationCopy(permission: BackendPermission, resourceKey: string, operationKey: string) {
  const copy = PERMISSION_COPY[permission.permission]
  return {
    key: operationKey,
    label: copy?.label ?? OPERATION_LABELS[operationKey] ?? operationKey,
    description:
      copy?.description ??
      (permission.name !== permission.permission
        ? permission.name
        : `允许${OPERATION_LABELS[operationKey] ?? operationKey}${resourceKey}`),
  }
}

const concretePermissions = computed(() =>
  permissions.value.filter((permission) => isConcretePermission(permission.permission)),
)

const permissionGroups = computed<PermissionGroup[]>(() => {
  const query = permissionQuery.value.trim().toLowerCase()
  const groups = new Map<string, Map<string, BackendPermission[]>>()

  concretePermissions.value
    .filter((permission) => {
      if (!query) return true
      const [moduleKey, resourceKey, operationKey] = splitPermission(permission.permission) ?? [
        '',
        '',
        '',
      ]
      const copy = operationCopy(permission, resourceKey, operationKey)
      return [moduleKey, resourceKey, operationKey, copy.label, copy.description].some((value) =>
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
      resources: [...resources.entries()]
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([resource, items]) => ({
          key: `${domain}:${resource}`,
          items: items
            .sort((left, right) => left.permission.localeCompare(right.permission))
            .map((permission) => {
              const [, , operation] = splitPermission(permission.permission) ?? ['', '', '']
              return {
                ...permission,
                ...operationCopy(permission, resource, operation),
              }
            }),
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

function permissionCount(items: PermissionOperation[]): string {
  const selected = new Set(selectedRolePermissionIds.value)
  return `${items.filter((item) => selected.has(item.id)).length}/${items.length}`
}

function setPermissionSelection(items: PermissionOperation[], checked: boolean): void {
  const ids = new Set(selectedRolePermissionIds.value)
  items.forEach((item) => (checked ? ids.add(item.id) : ids.delete(item.id)))
  selectedRolePermissionIds.value = [...ids]
}

function setGroupSelection(group: PermissionGroup, checked: boolean): void {
  setPermissionSelection(
    group.resources.flatMap((resource) => resource.items),
    checked,
  )
}

function selectionState(items: PermissionOperation[]): {
  checked: boolean
  indeterminate: boolean
} {
  const selected = items.filter((item) => selectedRolePermissionIds.value.includes(item.id))
  return {
    checked: items.length > 0 && selected.length === items.length,
    indeterminate: selected.length > 0 && selected.length < items.length,
  }
}

function eventChecked(event: Event): boolean {
  return event.target instanceof HTMLInputElement && event.target.checked
}

function expandPatternsToConcrete(ids: string[]): string[] {
  const patterns = permissions.value
    .filter((permission) => ids.includes(permission.id))
    .map((permission) => (permission.permission === '*' ? '*:*:*' : permission.permission))
    .filter(isPatternPermission)
  return concretePermissions.value
    .filter((permission) => patterns.some((pattern) => globMatches(pattern, permission.permission)))
    .map((permission) => permission.id)
}

function compressSelectionToPatterns(): string[] {
  const available = concretePermissions.value
  const selected = new Set(selectedRolePermissionIds.value)
  if (available.length > 0 && available.every((permission) => selected.has(permission.id))) {
    return ['*:*:*']
  }

  const result: string[] = []
  const allGroups = new Map<string, Map<string, BackendPermission[]>>()
  available.forEach((permission) => {
    const [moduleKey, resourceKey] = splitPermission(permission.permission) ?? ['', '']
    const resources = allGroups.get(moduleKey) ?? new Map<string, BackendPermission[]>()
    resources.set(resourceKey, [...(resources.get(resourceKey) ?? []), permission])
    allGroups.set(moduleKey, resources)
  })

  allGroups.forEach((resources, moduleKey) => {
    const completeModule = [...resources.values()].every((items) =>
      items.every((permission) => selected.has(permission.id)),
    )
    if (completeModule) {
      result.push(`${moduleKey}:*:*`)
      return
    }
    resources.forEach((items, resourceKey) => {
      if (items.every((permission) => selected.has(permission.id))) {
        result.push(`${moduleKey}:${resourceKey}:*`)
      } else {
        items
          .filter((permission) => selected.has(permission.id))
          .forEach((permission) => result.push(permission.permission))
      }
    })
  })
  return [...new Set(result)]
}

function setInitialSelection(): void {
  if (!selectedRoleId.value) selectedRoleId.value = roles.value[0]?.id ?? null
  if (openGroups.value.length === 0)
    openGroups.value = permissionGroups.value.map((group) => group.key)
}

async function loadRolePermissions(): Promise<void> {
  if (!canListRoles.value || loadedRolePermissions.value) return
  const [nextRoles, nextPermissions] = await Promise.all([
    systemApi.listRolePermissions(),
    systemApi.listPermissions(),
  ])
  roles.value = nextRoles
  permissions.value = nextPermissions
  roles.value.forEach((role) => {
    role.permissionIds = expandPatternsToConcrete(role.permissionIds)
  })
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
  initialUserSnapshots.value = Object.fromEntries(
    users.value.map((user) => [user.id, { enabled: user.enabled, roleIds: [...user.roleIds] }]),
  )
  userPage.value = 1
  loadedUserRoles.value = true
  setInitialSelection()
}

async function loadCurrentTab(force = false): Promise<void> {
  isRefreshing.value = true
  try {
    if (!session.user) await session.loadProfile()
    if (force) {
      if (activeTab.value === 'role-permissions') loadedRolePermissions.value = false
      else loadedUserRoles.value = false
    }
    if (activeTab.value === 'role-permissions') await loadRolePermissions()
    else await loadUserRoles()
    loadFailed.value = false
  } catch (error) {
    loadFailed.value = true
    message.error(error instanceof ApiError ? error.message : '权限数据加载失败，请稍后重试')
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
  try {
    await systemApi.updateRolePermissions(selectedRole.value.id, compressSelectionToPatterns())
    message.success('角色权限已保存')
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '角色权限保存失败，请稍后重试')
  } finally {
    isSaving.value = false
  }
}

async function saveUserRoles(): Promise<void> {
  if (!canUpdateUsers.value || changedUsers.value.length === 0) return
  isSaving.value = true
  try {
    await systemApi.updateUserRolesBatch(
      changedUsers.value.map((user) => ({
        userId: user.id,
        enabled: user.enabled,
        roleIds: user.roleIds,
      })),
    )
    initialUserSnapshots.value = Object.fromEntries(
      users.value.map((user) => [user.id, { enabled: user.enabled, roleIds: [...user.roleIds] }]),
    )
    message.success('用户角色与启用状态已保存')
  } catch (error) {
    message.error(
      error instanceof ApiError ? error.message : '用户角色与启用状态保存失败，请稍后重试',
    )
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

      <BaseSurface v-else-if="loadFailed" padding="large">
        <p class="system-page__empty">权限数据没能加载出来。</p>
        <BaseButton size="small" :loading="isRefreshing" @click="loadCurrentTab(true)">
          重试
        </BaseButton>
      </BaseSurface>

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
                <input v-model="permissionQuery" type="search" placeholder="搜索模块、资源或操作" />
              </label>
              <span class="system-page__count"
                >{{ selectedRolePermissionIds.length }} 项已选择</span
              >
            </div>

            <div class="permission-groups">
              <article v-for="group in permissionGroups" :key="group.key" class="permission-group">
                <div class="permission-group__heading">
                  <div class="permission-level permission-level--module">
                    <input
                      type="checkbox"
                      :checked="
                        selectionState(group.resources.flatMap((resource) => resource.items))
                          .checked
                      "
                      :indeterminate="
                        selectionState(group.resources.flatMap((resource) => resource.items))
                          .indeterminate
                      "
                      :disabled="!canUpdateRoles"
                      :aria-label="`选择模块 ${group.key}`"
                      @change="setGroupSelection(group, eventChecked($event))"
                    />
                    <button
                      type="button"
                      class="permission-group__toggle"
                      @click="toggleGroup(group.key)"
                    >
                      <component
                        :is="isOpen(group.key, openGroups) ? ChevronDown : ChevronRight"
                        :size="15"
                        aria-hidden="true"
                      />
                      <code>{{ group.key }}</code>
                      <small>{{ group.resources.length }} 个资源</small>
                    </button>
                  </div>
                </div>
                <div v-if="isOpen(group.key, openGroups)" class="permission-group__body">
                  <section
                    v-for="resource in group.resources"
                    :key="resource.key"
                    class="permission-resource"
                  >
                    <div class="permission-resource__heading">
                      <label class="permission-resource__identity" :title="`资源 ${resource.key}`">
                        <input
                          type="checkbox"
                          :checked="selectionState(resource.items).checked"
                          :indeterminate="selectionState(resource.items).indeterminate"
                          :disabled="!canUpdateRoles"
                          :aria-label="`选择资源 ${resource.key}`"
                          @change="setPermissionSelection(resource.items, eventChecked($event))"
                        />
                        <code>{{ resource.key.split(':')[1] }}</code>
                        <small>{{ permissionCount(resource.items) }}</small>
                      </label>
                      <div
                        class="permission-resource__operations"
                        :aria-label="`资源 ${resource.key} 的操作`"
                      >
                        <label
                          v-for="permission in resource.items"
                          :key="permission.id"
                          class="permission-operation"
                          :title="permission.description"
                        >
                          <input
                            v-model="selectedRolePermissionIds"
                            type="checkbox"
                            :value="permission.id"
                            :disabled="!canUpdateRoles"
                          />
                          <span>{{ permission.key }}</span>
                        </label>
                      </div>
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
              :disabled="changedUsers.length === 0"
              @click="saveUserRoles"
            >
              保存变更
            </BaseButton>
          </div>
          <div class="user-role-toolbar">
            <label class="system-page__filter">
              <span>筛选用户</span>
              <input v-model="userQuery" type="search" placeholder="搜索邮箱" />
            </label>
            <span class="system-page__count"
              >{{ filteredUsers.length }} 位用户 · {{ changedUsers.length }} 项待保存</span
            >
          </div>
          <div v-if="users.length" class="user-role-table-wrap">
            <div
              class="user-role-table"
              :style="{
                '--role-columns': `minmax(16rem, 1.5fr) repeat(${roleSummaries.length}, minmax(7rem, 0.7fr))`,
              }"
            >
              <div class="user-role-table__row user-role-table__row--header">
                <strong>用户</strong>
                <strong v-for="role in roleSummaries" :key="role.id">{{ role.name }}</strong>
              </div>
              <div v-for="user in pagedUsers" :key="user.id" class="user-role-table__row">
                <div class="user-role-table__user">
                  <label
                    class="user-role-table__status"
                    :title="user.enabled ? '停用用户' : '启用用户'"
                  >
                    <input
                      v-model="user.enabled"
                      type="checkbox"
                      :disabled="!canUpdateUsers"
                      :aria-label="`${user.email} 的启用状态`"
                    />
                  </label>
                  <div>
                    <strong>{{ user.email }}</strong>
                    <small>{{ user.enabled ? '已启用' : '已停用' }}</small>
                  </div>
                </div>
                <label v-for="role in roleSummaries" :key="role.id" class="user-role-table__check">
                  <input
                    v-model="user.roleIds"
                    type="checkbox"
                    :value="role.id"
                    :disabled="!canUpdateUsers || !user.enabled"
                  />
                  <span>{{ role.code }}</span>
                </label>
              </div>
            </div>
          </div>
          <p v-else class="system-page__empty">暂无可管理的用户。</p>
          <div v-if="filteredUsers.length" class="user-role-pagination">
            <span
              >显示 {{ (userPage - 1) * userPageSize + 1 }}-{{
                Math.min(userPage * userPageSize, filteredUsers.length)
              }}
              / {{ filteredUsers.length }}</span
            >
            <div>
              <button type="button" :disabled="userPage === 1" @click="userPage -= 1">
                上一页
              </button>
              <strong>{{ userPage }} / {{ userPageCount }}</strong>
              <button type="button" :disabled="userPage === userPageCount" @click="userPage += 1">
                下一页
              </button>
            </div>
          </div>
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
  grid-template-columns: minmax(16rem, 0.28fr) minmax(0, 1fr);
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
  box-shadow: 0 0.35rem 1.2rem rgb(20 31 61 / 4%);
  /*
   * 这两层都是网格项（.permission-group 在 .permission-groups 里，
   * .permission-resource 在 .permission-group__body 里），而网格项的自动最小尺寸是
   * **min-content** —— 里面有 nowrap 的操作项和一排勾选框，min-content 很大。
   *
   * 不写这一行的后果不是「挤一点」，而是：网格轨道按 min-content 撑到 700 多像素、
   * 比父容器还宽，整条链一起被顶宽，于是 .permission-resource__operations 永远有余量、
   * 永远不产生溢出 —— 它身上的 overflow-x: auto 就成了摆设，页面反而整体横向溢出。
   *
   * 写成 0 之后轨道可以收缩到容器宽度，操作区才会真的溢出并出现横向滚动条。
   * 行高不受影响：布局方向没变，只是允许它变窄。
   */
  min-inline-size: 0;
}

.permission-group__heading,
.permission-resource__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sys-space-3);
  padding: var(--sys-space-3) var(--sys-space-4);
}

.permission-group__heading {
  min-block-size: 3.25rem;
  background: color-mix(
    in srgb,
    var(--sys-color-action-primary) 4%,
    var(--sys-color-surface-raised)
  );
}

.permission-level {
  display: flex;
  min-inline-size: 0;
  align-items: center;
  gap: var(--sys-space-3);
}

.permission-level input,
.permission-resource__identity input,
.permission-operation input {
  accent-color: var(--sys-color-action-primary);
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

.permission-group__toggle code,
.permission-resource__identity code,
.permission-operation span {
  color: var(--sys-color-text);
  font-family: var(--ref-font-mono);
  font-size: 0.86rem;
}

.permission-resource__toggle strong {
  font: var(--sys-typography-body-compact);
}

.permission-group__toggle small,
.permission-resource__toggle small {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.permission-group__toggle svg {
  flex: 0 0 auto;
  color: var(--sys-color-text-muted);
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
  display: flex;
  min-inline-size: 0;
  gap: var(--sys-space-4);
  padding: var(--sys-space-3) var(--sys-space-4);
}

.permission-resource__identity {
  display: flex;
  min-inline-size: 9rem;
  flex: 0 0 9rem;
  align-items: center;
  gap: var(--sys-space-3);
  cursor: help;
}

.permission-resource__identity small {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.permission-resource__operations {
  display: flex;
  min-inline-size: 0;
  flex: 1;
  align-items: center;
  gap: var(--sys-space-5);
  overflow-x: auto;
  padding-block: 0.15rem;
  white-space: nowrap;
}

/*
 * 窄屏：整行一起左右滚动（资源名 + 操作项）。
 *
 * 原来只有 .permission-resource__operations 自己滚、资源名固定占 9rem，结果留给操作的
 * 滚动区很窄（420px 视口下只有 126px、375px 下只有 81px），滑起来憋屈。
 *
 * 改成把滚动容器**上移到 heading 这一层**：
 *   · heading 溢出并横滑，identity 和 operations 都 `flex: 0 0 auto` 保持自然宽度 ——
 *     两者一起移进移出，操作区能用上整行宽度；
 *   · operations 不再自己滚，否则会出现「滚动条套滚动条」，手势落在里层就滚不动外层。
 *
 * ⚠️ 这段必须放在上面两条基础规则**之后**：同优先级下后写的赢，而 scoped 样式不改变
 * 层叠顺序。写在前面会被 `flex: 1` / `overflow-x: auto` 直接盖掉，看起来"改了没生效"。
 *
 * 行高不变：布局方向仍是 row，只是允许它横向滚动。
 */
@media (max-width: 48rem) {
  .permission-resource__heading {
    overflow-x: auto;
  }

  .permission-resource__identity {
    /* 原来是 flex: 0 0 9rem，这里保持不收缩，跟着一起滚 */
    flex: 0 0 auto;
  }

  .permission-resource__operations {
    flex: 0 0 auto;
    min-inline-size: auto;
    overflow-x: visible;
  }
}

.permission-operation {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 0.45rem;
  cursor: help;
}

.permission-operation span {
  color: var(--sys-color-text-muted);
}

.permission-operation:hover span {
  color: var(--sys-color-action-primary);
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
  max-inline-size: none;
}

.user-role-toolbar {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--sys-space-4);
  margin-block-end: var(--sys-space-5);
}

.user-role-table-wrap {
  overflow-x: auto;
  border: 1px solid var(--sys-color-border);
  border-radius: var(--cmp-surface-radius);
}

.user-role-table {
  min-inline-size: max(100%, 48rem);
}

.user-role-table__row {
  display: grid;
  grid-template-columns: var(--role-columns);
  min-block-size: 3.5rem;
  align-items: center;
  border-block-end: 1px solid var(--sys-color-border);
}

.user-role-table__row:last-child {
  border-block-end: 0;
}

.user-role-table__row--header {
  min-block-size: 2.75rem;
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-label);
}

.user-role-table__row > * {
  min-inline-size: 0;
  padding-inline: var(--sys-space-4);
}

.user-role-table__user {
  display: flex;
  min-inline-size: 0;
  align-items: center;
  gap: var(--sys-space-3);
}

.user-role-table__user > div {
  display: grid;
  min-inline-size: 0;
  gap: 0.2rem;
}

.user-role-table__status {
  display: inline-grid;
  flex: 0 0 auto;
  place-items: center;
}

.user-role-table__user strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-role-table__user small {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.user-role-table__check {
  display: inline-flex;
  align-items: center;
  gap: var(--sys-space-2);
  color: var(--sys-color-text-muted);
  cursor: pointer;
  font: var(--sys-typography-caption);
}

.user-role-table__check input {
  accent-color: var(--sys-color-action-primary);
}

.user-role-table__status input {
  inline-size: 1rem;
  block-size: 1rem;
  accent-color: var(--sys-color-action-primary);
}

.user-role-table__check:has(input:checked) {
  color: var(--sys-color-action-primary);
  font-weight: 650;
}

.user-role-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sys-space-4);
  margin-block-start: var(--sys-space-4);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.user-role-pagination > div {
  display: inline-flex;
  align-items: center;
  gap: var(--sys-space-2);
}

.user-role-pagination button {
  min-block-size: 2rem;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.45rem;
  padding-inline: 0.7rem;
  background: var(--sys-color-surface-raised);
  color: var(--sys-color-text);
  cursor: pointer;
  font: var(--sys-typography-caption);
}

.user-role-pagination button:hover:not(:disabled) {
  border-color: var(--sys-color-action-primary);
  color: var(--sys-color-action-primary);
}

.user-role-pagination button:disabled {
  cursor: not-allowed;
  opacity: var(--sys-opacity-disabled);
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

  .access-role-list {
    grid-template-columns: 1fr;
  }

  .user-role-toolbar,
  .user-role-pagination {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
