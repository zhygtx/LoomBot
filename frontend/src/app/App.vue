<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import { MessageHost } from '@shared/ui'

import AppShell from './layout/AppShell.vue'

const route = useRoute()
const showShell = computed(() => Boolean(route.meta.requiresAuth) && !route.meta.standalone)
</script>

<template>
  <AppShell v-if="showShell">
    <RouterView />
  </AppShell>
  <RouterView v-else />

  <!-- 提示条挂在最外层：登录页、找回密码页也要能弹，不能只挂在带外壳的布局里 -->
  <MessageHost />
</template>
