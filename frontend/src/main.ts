import { createPinia } from 'pinia'
import { createApp } from 'vue'
import { VueQueryPlugin } from '@tanstack/vue-query'

import App from '@app/App.vue'
import { queryClient } from '@app/providers/query-client'
import { router } from '@app/router'
import { applyTheme } from '@shared/theme'

import '@/styles/index.css'

applyTheme('default')

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(VueQueryPlugin, { queryClient })
app.mount('#app')
