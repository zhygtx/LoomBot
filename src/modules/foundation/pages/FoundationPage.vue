<script setup lang="ts">
import { BaseButton, BaseSurface } from '@shared/ui'
</script>

<template>
  <main class="foundation-page">
    <BaseSurface as="section" class="foundation-card">
      <span class="foundation-eyebrow">Loom Frontend Foundation</span>
      <h1>新前端基础结构已建立</h1>
      <p>
        当前只包含应用外壳、模块边界、设计令牌与公共 UI 示例。认证和链接管理将在确认页面方案后接入。
      </p>
      <div class="foundation-actions">
        <BaseButton>主要操作</BaseButton>
        <BaseButton appearance="secondary">次要操作</BaseButton>
      </div>
    </BaseSurface>
  </main>
</template>

<style scoped>
.foundation-page {
  display: grid;
  min-block-size: 100dvh;
  padding: var(--sys-space-page);
  place-items: center;
}

.foundation-card {
  inline-size: min(100%, 44rem);
}

.foundation-eyebrow {
  color: var(--sys-color-text-accent);
  font: var(--sys-typography-label);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

h1 {
  margin-block: var(--sys-space-3) var(--sys-space-4);
  font: var(--sys-typography-display);
}

p {
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-body);
}

.foundation-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sys-space-3);
  margin-block-start: var(--sys-space-6);
}
</style>
