<script setup lang="ts">
withDefaults(
  defineProps<{
    tone?: 'danger' | 'info' | 'success' | 'warning'
    title?: string
  }>(),
  { tone: 'info', title: '' },
)
</script>

<template>
  <div class="base-notice" :class="`base-notice--${tone}`" role="status">
    <span class="base-notice__mark" aria-hidden="true" />
    <div>
      <strong v-if="title">{{ title }}</strong>
      <div class="base-notice__content"><slot /></div>
    </div>
  </div>
</template>

<style scoped>
.base-notice {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: start;
  border: 1px solid var(--notice-border);
  border-radius: var(--ref-radius-medium);
  padding: var(--sys-space-3) var(--sys-space-4);
  background: var(--notice-background);
  color: var(--notice-text);
  font: var(--sys-typography-caption);
  gap: var(--sys-space-3);
}

.base-notice--info {
  --notice-border: var(--sys-color-info-border);
  --notice-background: var(--sys-color-info-subtle);
  --notice-text: var(--sys-color-info-text);
}

.base-notice--success {
  --notice-border: var(--sys-color-success-border);
  --notice-background: var(--sys-color-success-subtle);
  --notice-text: var(--sys-color-success-text);
}

.base-notice--warning {
  --notice-border: var(--sys-color-warning-border);
  --notice-background: var(--sys-color-warning-subtle);
  --notice-text: var(--sys-color-warning-text);
}

.base-notice--danger {
  --notice-border: var(--sys-color-danger-border);
  --notice-background: var(--sys-color-danger-subtle);
  --notice-text: var(--sys-color-danger-text);
}

.base-notice__mark {
  inline-size: 0.5rem;
  block-size: 0.5rem;
  margin-block-start: 0.35rem;
  border-radius: 50%;
  background: currentcolor;
}

.base-notice strong {
  display: block;
  margin-block-end: var(--sys-space-1);
}
</style>
