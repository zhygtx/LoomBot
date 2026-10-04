import { createRouter, createWebHistory } from 'vue-router'

import { readStoredToken } from '@shared/session/storage'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'welcome',
      component: () => import('@modules/foundation/pages/FoundationPage.vue'),
      meta: { title: '首页', requiresAuth: true },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@modules/auth/pages/LoginPage.vue'),
      meta: { title: '登录', publicOnly: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@modules/auth/pages/RegisterPage.vue'),
      meta: { title: '注册', publicOnly: true },
    },
    {
      path: '/forgot-password',
      name: 'forgot-password',
      component: () => import('@modules/auth/pages/ForgotPasswordPage.vue'),
      meta: { title: '找回密码', publicOnly: true },
    },
    {
      path: '/system/permissions',
      name: 'system-permissions',
      component: () => import('@modules/system/pages/PermissionManagementPage.vue'),
      meta: { title: '权限管理', requiresAuth: true },
    },
    {
      path: '/system/menus',
      name: 'system-menus',
      component: () => import('@modules/system/pages/MenuManagementPage.vue'),
      meta: { title: '菜单管理', requiresAuth: true },
    },
    {
      path: '/system/config',
      name: 'system-config',
      component: () => import('@modules/system/pages/SystemConfigPage.vue'),
      meta: { title: '系统配置', requiresAuth: true },
    },
    {
      path: '/connections',
      name: 'connections',
      component: () => import('@modules/connections/pages/ConnectionsPage.vue'),
      meta: { title: '连接测试台', requiresAuth: true },
    },
    {
      path: '/workflow/list',
      name: 'workflow-list',
      component: () => import('@modules/workflow/pages/WorkflowListPage.vue'),
      meta: { title: '工作流列表', requiresAuth: true },
    },
    {
      path: '/workflow/log',
      name: 'workflow-log',
      component: () => import('@modules/workflow/pages/WorkflowLogPage.vue'),
      meta: { title: '执行日志', requiresAuth: true },
    },
    {
      path: '/workflow/edit/:id',
      name: 'workflow-edit',
      component: () => import('@modules/workflow/pages/WorkflowEditPage.vue'),
      meta: { title: '工作流编辑', requiresAuth: true, standalone: true },
    },
    {
      path: '/workflow/edit',
      name: 'workflow-create',
      component: () => import('@modules/workflow/pages/WorkflowEditPage.vue'),
      meta: { title: '新建工作流', requiresAuth: true, standalone: true },
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  const hasToken = Boolean(readStoredToken())
  if (to.meta.requiresAuth && !hasToken) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  if (to.meta.publicOnly && hasToken) return { name: 'welcome' }
  return true
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : ''
  document.title = title ? `${title} · LoomBot` : 'LoomBot'
})
