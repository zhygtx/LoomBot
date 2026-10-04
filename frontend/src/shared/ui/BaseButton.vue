<script setup lang="ts">
import { computed } from 'vue'

type ButtonAppearance = 'primary' | 'secondary' | 'ghost' | 'success' | 'warning' | 'danger'
type ButtonSize = 'small' | 'medium' | 'large'

const props = withDefaults(
  defineProps<{
    appearance?: ButtonAppearance
    size?: ButtonSize
    disabled?: boolean
    loading?: boolean
    type?: 'button' | 'submit' | 'reset'
  }>(),
  {
    appearance: 'primary',
    size: 'medium',
    disabled: false,
    loading: false,
    type: 'button',
  },
)

const emit = defineEmits<{
  click: [event: MouseEvent]
}>()

const isDisabled = computed(() => props.disabled || props.loading)
</script>

<template>
  <button
    class="base-button"
    :class="[`base-button--${appearance}`, `base-button--${size}`]"
    :type="type"
    :disabled="isDisabled"
    :aria-busy="loading"
    @click="emit('click', $event)"
  >
    <span v-if="loading" class="base-button__loader" aria-hidden="true" />
    <slot />
  </button>
</template>

<style scoped>
.base-button {
  display: inline-flex;
  min-inline-size: max-content;
  align-items: center;
  justify-content: center;
  border: var(--cmp-button-border-width) solid transparent;
  border-radius: var(--cmp-button-radius);
  font: var(--cmp-button-font);
  gap: var(--cmp-button-gap);
  transition:
    background-color var(--sys-motion-fast),
    border-color var(--sys-motion-fast),
    color var(--sys-motion-fast),
    transform var(--sys-motion-fast);
}

.base-button:not(:disabled) {
  cursor: pointer;
}

.base-button:not(:disabled):active {
  transform: translateY(1px);
}

.base-button:focus-visible {
  outline: var(--sys-focus-width) solid var(--sys-color-focus);
  outline-offset: var(--sys-focus-offset);
}

.base-button:disabled {
  cursor: not-allowed;
  opacity: var(--sys-opacity-disabled);
}

.base-button--small {
  min-block-size: var(--cmp-button-height-small);
  padding-inline: var(--cmp-button-padding-small);
}

.base-button--medium {
  min-block-size: var(--cmp-button-height-medium);
  padding-inline: var(--cmp-button-padding-medium);
}

.base-button--large {
  min-block-size: var(--cmp-button-height-large);
  padding-inline: var(--cmp-button-padding-large);
}

.base-button--primary {
  background: var(--sys-color-action-primary);
  color: var(--sys-color-on-action-primary);
}

.base-button--primary:not(:disabled):hover {
  background: var(--sys-color-action-primary-hover);
}

.base-button--secondary {
  border-color: var(--sys-color-border-strong);
  background: var(--sys-color-surface-raised);
  color: var(--sys-color-text);
}

.base-button--secondary:not(:disabled):hover,
.base-button--ghost:not(:disabled):hover {
  background: var(--sys-color-action-subtle-hover);
}

.base-button--ghost {
  background: transparent;
  color: var(--sys-color-text);
}

.base-button--danger {
  background: var(--sys-color-danger);
  color: var(--sys-color-on-danger);
}

.base-button--success {
  background: var(--sys-color-success-text);
  color: var(--sys-color-surface-raised);
}

.base-button--success:not(:disabled):hover {
  background: color-mix(in srgb, var(--sys-color-success-text) 86%, var(--sys-color-text));
}

.base-button--warning {
  background: var(--sys-color-warning-text);
  color: var(--sys-color-surface-raised);
}

.base-button--warning:not(:disabled):hover {
  background: color-mix(in srgb, var(--sys-color-warning-text) 86%, var(--sys-color-text));
}

.base-button__loader {
  inline-size: 1em;
  block-size: 1em;
  border: 0.125em solid currentcolor;
  border-inline-end-color: transparent;
  border-radius: 50%;
  animation: spin 0.75s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .base-button,
  .base-button__loader {
    transition: none;
    animation: none;
  }
}
</style>
