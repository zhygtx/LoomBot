<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ApiError } from '@shared/api/http-client'
import { BaseButton, BaseSurface, iconFor, message } from '@shared/ui'

import { useSessionStore } from '../../auth/model/session-store'
import { systemApi } from '../../system/api/system-api'
import type { MenuItem } from '../../system/model/types'

const session = useSessionStore()
const menus = ref<MenuItem[]>([])
const loading = ref(true)
/** 加载失败时留一个常驻的空态说明，见决策记录 D72：会过期的提示走提示条，状态要留在页面上。 */
const loadFailed = ref(false)

const quickLinks = computed(() =>
  menus.value
    .filter((menu) => menu.type === 'MENU' && menu.path)
    .sort((a, b) => a.sort - b.sort)
    .slice(0, 4),
)

async function loadNavigation(): Promise<void> {
  loading.value = true
  try {
    if (!session.user) await session.loadProfile()
    menus.value = await systemApi.navigation()
    loadFailed.value = false
  } catch (error) {
    loadFailed.value = true
    message.error(error instanceof ApiError ? error.message : '工作台数据加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

onMounted(loadNavigation)
</script>

<template>
  <main class="foundation-page">
    <header class="foundation-hero">
      <div>
        <span class="foundation-eyebrow">Loom / Workspace</span>
        <h1>工作台</h1>
        <p>欢迎回来，{{ session.user?.email }}。从这里进入常用功能。</p>
      </div>
      <BaseButton appearance="secondary" size="small" :loading="loading" @click="loadNavigation">
        刷新状态
      </BaseButton>
    </header>

    <section class="foundation-welcome" aria-label="工作台概览">
      <BaseSurface padding="large" class="foundation-welcome__surface">
        <div>
          <span class="foundation-card__kicker">Workspace overview</span>
          <h2>保持专注，快速进入工作流</h2>
          <p>侧边栏会根据当前账号的菜单可见性展示功能，页面权限仍由后端独立校验。</p>
        </div>
        <span class="foundation-welcome__mark">
          <component :is="iconFor('sparkles')" :size="30" />
        </span>
      </BaseSurface>
    </section>

    <section class="foundation-section" aria-labelledby="quick-links-title">
      <div class="foundation-section__heading">
        <div>
          <span class="foundation-card__kicker">Quick access</span>
          <h2 id="quick-links-title">常用入口</h2>
        </div>
        <span class="foundation-section__hint">{{ quickLinks.length }} 个可用入口</span>
      </div>

      <div v-if="loading" class="foundation-empty">正在加载入口…</div>
      <div v-else-if="loadFailed" class="foundation-empty">
        入口没能加载出来，上面的提示条几秒后会消失，可以点右上角「刷新状态」重试。
      </div>
      <div v-else-if="!quickLinks.length" class="foundation-empty">
        暂无可用入口，请先配置菜单。
      </div>
      <div v-else class="foundation-quick-grid">
        <RouterLink
          v-for="menu in quickLinks"
          :key="menu.id"
          class="foundation-quick-card"
          :to="menu.path!"
        >
          <span class="foundation-quick-card__icon">
            <component :is="iconFor(menu.iconKey)" :size="20" />
          </span>
          <span class="foundation-quick-card__copy">
            <strong>{{ menu.name }}</strong>
            <small>{{ menu.remark || menu.path }}</small>
          </span>
          <span class="foundation-quick-card__arrow">
            <component :is="iconFor('arrow')" :size="18" />
          </span>
        </RouterLink>
      </div>
    </section>
  </main>
</template>

<style scoped>
.foundation-page {
  display: grid;
  gap: var(--sys-space-7);
  inline-size: min(100%, 78rem);
  margin-inline: auto;
}

.foundation-hero {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--sys-space-5);
  padding-block: var(--sys-space-2) var(--sys-space-3);
}

.foundation-eyebrow,
.foundation-card__kicker {
  color: var(--sys-color-text-accent);
  font: var(--sys-typography-label);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

h1 {
  margin: var(--sys-space-2) 0 var(--sys-space-3);
  font: var(--sys-typography-display);
}

h2 {
  margin: var(--sys-space-2) 0 0;
  font: 700 clamp(1.25rem, 2vw, 1.7rem) / 1.2 var(--ref-font-sans);
}

.foundation-hero p,
.foundation-welcome p {
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-body-compact);
}

.foundation-welcome__surface {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sys-space-6);
  border-color: color-mix(in srgb, var(--sys-color-action-primary) 16%, var(--sys-color-border));
  background:
    radial-gradient(
      circle at 90% 20%,
      color-mix(in srgb, var(--sys-color-action-primary) 12%, transparent),
      transparent 18rem
    ),
    var(--sys-color-surface-raised);
}

.foundation-welcome__mark {
  display: grid;
  inline-size: 4.5rem;
  block-size: 4.5rem;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 1.5rem;
  background: color-mix(
    in srgb,
    var(--sys-color-action-primary) 10%,
    var(--sys-color-surface-muted)
  );
  color: var(--sys-color-action-primary);
  font-size: 2rem;
}

.foundation-section {
  display: grid;
  gap: var(--sys-space-4);
}

.foundation-section__heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--sys-space-4);
}

.foundation-section__hint {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.foundation-quick-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sys-space-4);
}

.foundation-quick-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--sys-space-4);
  border: 1px solid var(--sys-color-border);
  border-radius: var(--cmp-surface-radius);
  background: var(--sys-color-surface-raised);
  padding: var(--sys-space-5);
  color: var(--sys-color-text);
  text-decoration: none;
  transition:
    border-color var(--sys-motion-fast),
    background var(--sys-motion-fast),
    transform var(--sys-motion-fast);
}

.foundation-quick-card:hover {
  border-color: color-mix(in srgb, var(--sys-color-action-primary) 42%, var(--sys-color-border));
  background: color-mix(
    in srgb,
    var(--sys-color-action-primary) 5%,
    var(--sys-color-surface-raised)
  );
  transform: translateY(-0.1rem);
}

.foundation-quick-card__icon {
  display: grid;
  inline-size: 2.5rem;
  block-size: 2.5rem;
  place-items: center;
  border-radius: 0.8rem;
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-action-primary);
  font-weight: 700;
}

.foundation-quick-card__copy {
  display: grid;
  min-inline-size: 0;
  gap: 0.25rem;
}

.foundation-quick-card__copy strong {
  font: var(--sys-typography-body-compact);
}

.foundation-quick-card__copy small {
  overflow: hidden;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.foundation-quick-card__arrow {
  color: var(--sys-color-text-accent);
  font-size: 1.1rem;
}

.foundation-empty {
  border: 1px dashed var(--sys-color-border-strong);
  border-radius: var(--cmp-surface-radius);
  padding: var(--sys-space-7);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-body-compact);
  text-align: center;
}

@media (max-width: 48rem) {
  .foundation-hero,
  .foundation-section__heading {
    align-items: start;
    flex-direction: column;
  }

  .foundation-welcome__surface {
    align-items: start;
  }

  .foundation-quick-grid {
    grid-template-columns: 1fr;
  }
}
</style>
