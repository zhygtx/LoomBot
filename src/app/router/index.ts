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
  document.title = title ? `${title} · Loom` : 'Loom'
})
