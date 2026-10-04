<script setup lang="ts">
import type { Component } from 'vue'
import { CircleAlert, CircleCheck, Info, TriangleAlert, X } from '@lucide/vue'

import { dismissMessage, messageItems, type MessageType } from './message'

/**
 * 提示条图标。
 *
 * 四种类型各配一个图形，而不是像 BaseNotice 那样只点一个小圆点：
 * 提示条是**扫一眼**的，圆点要靠颜色区分，图形不用 —— 色觉障碍用户也能分出来。
 */
const ICONS: Record<MessageType, Component> = {
  success: CircleCheck,
  error: CircleAlert,
  warning: TriangleAlert,
  info: Info,
}

/** 成功 / 普通信息是「告知」，错误 / 警告是「打断」，屏读器的播报方式不同。 */
function roleOf(type: MessageType): 'alert' | 'status' {
  return type === 'error' || type === 'warning' ? 'alert' : 'status'
}
</script>

<template>
  <div class="message-host">
    <TransitionGroup name="message">
      <div
        v-for="item in messageItems"
        :key="item.id"
        class="message"
        :class="`message--${item.type}`"
        :role="roleOf(item.type)"
      >
        <component :is="ICONS[item.type]" :size="17" class="message__icon" aria-hidden="true" />
        <p class="message__text">{{ item.text }}</p>
        <button
          class="message__close"
          type="button"
          aria-label="关闭提示"
          @click="dismissMessage(item.id)"
        >
          <X :size="14" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
/*
 * 固定在最上方居中，和 Element Plus 的默认位置一致。
 * 用 pointer-events: none 让整条提示带不挡住下面的点击，只有提示条本身可交互 ——
 * 否则页面顶部会凭空多出一条 2rem 高的「点不到」的区域。
 */
.message-host {
  position: fixed;
  z-index: 3000;
  inset-block-start: var(--sys-space-5);
  inset-inline: 0;
  display: grid;
  justify-items: center;
  gap: var(--sys-space-2);
  padding-inline: var(--sys-space-4);
  pointer-events: none;
}

.message {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: start;
  gap: var(--sys-space-3);
  min-inline-size: min(22rem, 100%);
  max-inline-size: min(34rem, 100%);
  border: 1px solid var(--message-border);
  border-radius: var(--ref-radius-medium);
  padding: var(--sys-space-3) var(--sys-space-3) var(--sys-space-3) var(--sys-space-4);
  background: var(--message-background);
  box-shadow: var(--sys-shadow-raised);
  color: var(--message-text);
  font: var(--sys-typography-body-compact);
  pointer-events: auto;
}

.message--success {
  --message-border: var(--sys-color-success-border);
  --message-background: var(--sys-color-success-subtle);
  --message-text: var(--sys-color-success-text);
}

.message--error {
  --message-border: var(--sys-color-danger-border);
  --message-background: var(--sys-color-danger-subtle);
  --message-text: var(--sys-color-danger-text);
}

.message--warning {
  --message-border: var(--sys-color-warning-border);
  --message-background: var(--sys-color-warning-subtle);
  --message-text: var(--sys-color-warning-text);
}

.message--info {
  --message-border: var(--sys-color-info-border);
  --message-background: var(--sys-color-info-subtle);
  --message-text: var(--sys-color-info-text);
}

.message__icon {
  margin-block-start: 0.15rem;
}

.message__text {
  margin: 0;
  overflow-wrap: anywhere;
}

.message__close {
  display: grid;
  inline-size: 1.5rem;
  block-size: 1.5rem;
  place-items: center;
  border: 0;
  border-radius: var(--ref-radius-small);
  background: transparent;
  color: inherit;
  cursor: pointer;
  opacity: 0.6;
}

.message__close:hover {
  background: color-mix(in srgb, currentcolor 12%, transparent);
  opacity: 1;
}

.message-enter-active,
.message-leave-active {
  transition:
    opacity var(--sys-motion-normal),
    transform var(--sys-motion-normal);
}

.message-enter-from,
.message-leave-to {
  opacity: 0;
  transform: translateY(-0.6rem);
}

/* 一条消失时其余的平滑上移，而不是瞬间跳位 */
.message-move {
  transition: transform var(--sys-motion-normal);
}

@media (prefers-reduced-motion: reduce) {
  .message-enter-active,
  .message-leave-active,
  .message-move {
    transition: none;
  }
}
</style>
