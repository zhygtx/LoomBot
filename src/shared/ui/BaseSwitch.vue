<script setup lang="ts">
/**
 * 开关。
 *
 * <p>为什么不是 checkbox：复选框表达的是「这一项被选中」，而配置项开关表达的是
 * 「这个功能现在开着」—— 后者是个**状态**，用开关（`role="switch"`）才能让屏读器
 * 念出「开/关」。复选框在屏读器里只会念「已选中/未选中」，读起来像在勾选清单。
 *
 * <p>用 `<button role="switch">` 而不是 `<input type="checkbox">`：input 的固有尺寸
 * 和默认外观在各浏览器里都要额外覆写，而且它的 focus 环和键盘行为要自己补；
 * button 天然可聚焦、可空格/回车触发。代价是 `aria-checked` 要手动同步 —— 这里
 * 直接绑到 `modelValue` 上，没有中间状态。
 */
withDefaults(
  defineProps<{
    modelValue: boolean
    disabled?: boolean
    /** 行内已经有可见文字时传空即可；独立出现时必须给一个无障碍名称 */
    ariaLabel?: string
  }>(),
  { disabled: false, ariaLabel: '' },
)

const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
</script>

<template>
  <button
    type="button"
    class="base-switch"
    :class="{ 'base-switch--on': modelValue, 'base-switch--disabled': disabled }"
    role="switch"
    :aria-checked="modelValue"
    :aria-label="ariaLabel || undefined"
    :disabled="disabled"
    @click="emit('update:modelValue', !modelValue)"
  >
    <span class="base-switch__track" aria-hidden="true">
      <span class="base-switch__thumb" />
    </span>
  </button>
</template>

<style scoped>
.base-switch {
  display: inline-flex;
  align-items: center;
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}

.base-switch__track {
  position: relative;
  display: inline-block;
  inline-size: 2.5rem;
  block-size: 1.4rem;
  border: 1px solid var(--sys-color-border-strong);
  border-radius: var(--ref-radius-round);
  background: var(--sys-color-surface-muted);
  transition:
    background var(--sys-motion-fast),
    border-color var(--sys-motion-fast);
}

.base-switch__thumb {
  position: absolute;
  inset-block-start: 0.125rem;
  inset-inline-start: 0.125rem;
  inline-size: 1rem;
  block-size: 1rem;
  border-radius: 50%;
  background: var(--sys-color-field);
  box-shadow: 0 1px 2px rgb(16 24 40 / 24%);
  transition: translate var(--sys-motion-fast);
}

.base-switch--on .base-switch__track {
  border-color: var(--sys-color-action-primary);
  background: var(--sys-color-action-primary);
}

.base-switch--on .base-switch__thumb {
  /* 位移而不是改 inset-inline-start：只动 transform 类属性，不触发布局 */
  translate: 1.1rem 0;
}

.base-switch:hover:not(.base-switch--disabled) .base-switch__track {
  border-color: var(--sys-color-border-interactive);
}

.base-switch:focus-visible {
  outline: var(--sys-focus-width) solid var(--sys-color-focus);
  outline-offset: var(--sys-focus-offset);
  border-radius: var(--ref-radius-round);
}

.base-switch--disabled {
  cursor: not-allowed;
  opacity: var(--sys-opacity-disabled);
}

@media (prefers-reduced-motion: reduce) {
  .base-switch__track,
  .base-switch__thumb {
    transition: none;
  }
}
</style>
