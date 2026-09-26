<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChevronDown, ChevronRight } from '@lucide/vue'

import { ApiError } from '@shared/api/http-client'
import { BaseNotice, iconFor, shellIcons } from '@shared/ui'

import { useSessionStore } from '../../modules/auth/model/session-store'
import { systemApi } from '../../modules/system/api/system-api'
import type { MenuItem } from '../../modules/system/model/types'

interface MenuNode extends MenuItem {
  children: MenuNode[]
}

interface SidebarEntry {
  node: MenuNode
  depth: number
}

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const menus = ref<MenuItem[]>([])
const openGroups = ref<string[]>([])
const isCollapsed = ref(false)
const isMobileOpen = ref(false)
const isLoading = ref(true)
const errorMessage = ref('')

const menuTree = computed(() => buildTree(menus.value))
const sidebarEntries = computed(() => flattenTree(menuTree.value))
const breadcrumbs = computed(() =>
  route.matched
    .filter((record) => typeof record.meta.title === 'string')
    .map((record) => ({ label: String(record.meta.title), path: record.path })),
)
const currentTitle = computed(() => String(route.meta.title ?? '工作台'))
const userEmail = computed(() => session.user?.email ?? '当前用户')
const userInitial = computed(() => userEmail.value.slice(0, 1).toUpperCase())

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
    entries.sort((a, b) => a.sort - b.sort || a.name.localeCompare(b.name, 'zh-CN'))
    entries.forEach((entry) => sortNodes(entry.children))
  }
  sortNodes(roots)
  return roots
}

function flattenTree(nodes: MenuNode[], depth = 0): SidebarEntry[] {
  const result: SidebarEntry[] = []
  const open = new Set(openGroups.value)
  nodes.forEach((node) => {
    result.push({ node, depth })
    if (node.type === 'CATALOG' && node.children.length && open.has(node.id)) {
      result.push(...flattenTree(node.children, depth + 1))
    }
  })
  return result
}

function isActive(node: MenuNode): boolean {
  return Boolean(node.path && (route.path === node.path || route.path.startsWith(node.path + '/')))
}

function isGroupOpen(node: MenuNode): boolean {
  return openGroups.value.includes(node.id)
}

function toggleGroup(node: MenuNode): void {
  openGroups.value = isGroupOpen(node)
    ? openGroups.value.filter((id) => id !== node.id)
    : [...openGroups.value, node.id]
}

function closeMobileMenu(): void {
  isMobileOpen.value = false
}

function toggleSidebar(): void {
  if (window.matchMedia('(max-width: 48rem)').matches) isMobileOpen.value = !isMobileOpen.value
  else isCollapsed.value = !isCollapsed.value
}

function isInternalPath(path: string | null): boolean {
  return Boolean(path?.startsWith('/'))
}

async function loadNavigation(): Promise<void> {
  isLoading.value = true
  errorMessage.value = ''
  try {
    if (!session.user) await session.loadProfile()
    menus.value = await systemApi.navigation()
    openGroups.value = menus.value.filter((item) => item.type === 'CATALOG').map((item) => item.id)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '导航加载失败，请稍后重试'
  } finally {
    isLoading.value = false
  }
}

function logout(): void {
  session.clear()
  void router.replace({ name: 'login' })
}

onMounted(loadNavigation)
</script>

<template>
  <div class="app-shell" :class="{ 'app-shell--collapsed': isCollapsed }">
    <button
      v-if="isMobileOpen"
      class="app-shell__scrim"
      type="button"
      aria-label="关闭菜单"
      @click="closeMobileMenu"
    />

    <aside class="app-shell__sidebar" :class="{ 'app-shell__sidebar--open': isMobileOpen }">
      <div class="app-shell__brand">
        <RouterLink class="app-shell__brand-link" to="/" @click="closeMobileMenu">
          <span class="app-shell__brand-mark">L</span>
          <span class="app-shell__brand-copy">
            <strong>Loom</strong>
            <small>Workspace</small>
          </span>
        </RouterLink>
        <button
          class="app-shell__collapse-button"
          type="button"
          :aria-label="isCollapsed ? '展开菜单' : '收起菜单'"
          @click="toggleSidebar"
        >
          <component :is="isCollapsed ? shellIcons.expand : shellIcons.collapse" :size="16" />
        </button>
      </div>

      <nav class="app-shell__nav" aria-label="主导航">
        <RouterLink
          class="app-shell__nav-link app-shell__nav-link--home"
          to="/"
          exact-active-class="app-shell__nav-link--active"
          @click="closeMobileMenu"
        >
          <span class="app-shell__nav-icon">
            <component :is="iconFor('home')" :size="18" />
          </span>
          <span class="app-shell__nav-label">工作台</span>
        </RouterLink>

        <div v-if="isLoading" class="app-shell__nav-loading">正在加载菜单…</div>
        <BaseNotice v-else-if="errorMessage" tone="danger">{{ errorMessage }}</BaseNotice>
        <template v-else>
          <template v-for="entry in sidebarEntries" :key="entry.node.id">
            <button
              v-if="entry.node.type === 'CATALOG' && entry.node.children.length"
              class="app-shell__nav-link app-shell__nav-link--group"
              :class="{ 'app-shell__nav-link--group-open': isGroupOpen(entry.node) }"
              type="button"
              :style="{ '--nav-depth': entry.depth }"
              @click="toggleGroup(entry.node)"
            >
              <span class="app-shell__nav-icon">
                <component :is="iconFor(entry.node.iconKey, 'layout')" :size="18" />
              </span>
              <span class="app-shell__nav-label">{{ entry.node.name }}</span>
              <component
                :is="isGroupOpen(entry.node) ? ChevronDown : ChevronRight"
                :size="16"
                class="app-shell__nav-chevron"
              />
            </button>
            <RouterLink
              v-else-if="entry.node.path && isInternalPath(entry.node.path)"
              class="app-shell__nav-link"
              :class="{ 'app-shell__nav-link--active': isActive(entry.node) }"
              :style="{ '--nav-depth': entry.depth }"
              :to="entry.node.path"
              @click="closeMobileMenu"
            >
              <span class="app-shell__nav-icon">
                <component :is="iconFor(entry.node.iconKey)" :size="18" />
              </span>
              <span class="app-shell__nav-label">{{ entry.node.name }}</span>
            </RouterLink>
          </template>
        </template>
      </nav>

      <div class="app-shell__sidebar-footer">
        <div class="app-shell__user">
          <span class="app-shell__user-avatar">{{ userInitial }}</span>
          <span class="app-shell__user-copy">
            <strong>{{ userEmail }}</strong>
            <small>已登录</small>
          </span>
        </div>
        <button class="app-shell__logout" type="button" @click="logout">
          <component :is="shellIcons.logout" :size="15" />
          <span>退出登录</span>
        </button>
      </div>
    </aside>

    <div class="app-shell__main">
      <header class="app-shell__topbar">
        <button
          class="app-shell__mobile-menu"
          type="button"
          aria-label="打开菜单"
          @click="toggleSidebar"
        >
          <component :is="shellIcons.menu" :size="19" />
        </button>
        <div class="app-shell__breadcrumbs" aria-label="面包屑">
          <RouterLink to="/">工作台</RouterLink>
          <template v-for="(crumb, index) in breadcrumbs" :key="crumb.path + index">
            <span aria-hidden="true">/</span>
            <span :class="{ 'app-shell__breadcrumb-current': index === breadcrumbs.length - 1 }">{{
              crumb.label
            }}</span>
          </template>
        </div>
        <div class="app-shell__topbar-actions">
          <span class="app-shell__page-title">{{ currentTitle }}</span>
          <button
            class="app-shell__refresh"
            type="button"
            aria-label="刷新导航"
            @click="loadNavigation"
          >
            <component :is="shellIcons.refresh" :size="17" />
          </button>
        </div>
      </header>

      <main class="app-shell__content">
        <slot />
      </main>
    </div>
  </div>
</template>
