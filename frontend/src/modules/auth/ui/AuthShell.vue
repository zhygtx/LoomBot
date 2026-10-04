<script setup lang="ts">
defineProps<{
  eyebrow: string
  title: string
  description: string
}>()
</script>

<template>
  <main class="auth-shell">
    <section class="auth-shell__story" aria-label="产品介绍">
      <RouterLink class="auth-shell__brand" to="/login" aria-label="LoomBot 首页">
        <span class="auth-shell__brand-mark" aria-hidden="true">L</span>
        <span>LoomBot</span>
      </RouterLink>

      <div class="auth-shell__story-content">
        <p class="auth-shell__kicker">连接 · 编排 · 自动化</p>
        <h2>让不同平台的消息与能力，在同一条链路中自然流动。</h2>
        <p>统一管理连接、权限和工作流，把复杂的机器人基础设施收敛成清晰可控的操作面。</p>
      </div>

      <div class="auth-shell__signal" aria-hidden="true">
        <span />
        <span />
        <span />
        <i />
      </div>

      <p class="auth-shell__footnote">个人项目早期阶段 · 默认站长权限便于功能联调</p>
    </section>

    <section class="auth-shell__panel">
      <div class="auth-shell__form-wrap">
        <header class="auth-shell__header">
          <span>{{ eyebrow }}</span>
          <h1>{{ title }}</h1>
          <p>{{ description }}</p>
        </header>
        <slot />
      </div>
    </section>
  </main>
</template>

<style scoped>
.auth-shell {
  display: grid;
  min-block-size: 100dvh;
  grid-template-columns: minmax(22rem, 0.9fr) minmax(32rem, 1.1fr);
  background: var(--sys-color-canvas);
}

.auth-shell__story {
  position: relative;
  display: flex;
  overflow: hidden;
  min-block-size: 100dvh;
  flex-direction: column;
  padding: clamp(2rem, 4vw, 4rem);
  background: var(--sys-color-brand-canvas);
  color: var(--sys-color-on-brand-canvas);
  isolation: isolate;
}

.auth-shell__story::before,
.auth-shell__story::after {
  position: absolute;
  z-index: -1;
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 50%;
  content: '';
}

.auth-shell__story::before {
  inline-size: 34rem;
  block-size: 34rem;
  inset: -11rem -13rem auto auto;
}

.auth-shell__story::after {
  inline-size: 24rem;
  block-size: 24rem;
  inset: auto auto -8rem -9rem;
}

.auth-shell__brand {
  display: inline-flex;
  align-items: center;
  align-self: flex-start;
  color: inherit;
  font: 700 1.125rem/1 var(--ref-font-sans);
  gap: var(--sys-space-3);
  text-decoration: none;
}

.auth-shell__brand-mark {
  display: grid;
  inline-size: 2.25rem;
  block-size: 2.25rem;
  border: 1px solid rgb(255 255 255 / 24%);
  border-radius: 0.75rem;
  background: rgb(255 255 255 / 12%);
  place-items: center;
}

.auth-shell__story-content {
  max-inline-size: 35rem;
  margin-block: auto;
}

.auth-shell__kicker {
  margin: 0 0 var(--sys-space-5);
  color: var(--sys-color-on-brand-muted);
  font: var(--sys-typography-label);
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.auth-shell__story-content h2 {
  margin: 0;
  font: 680 clamp(2.5rem, 5vw, 4.75rem) / 1.04 var(--ref-font-sans);
  letter-spacing: -0.055em;
}

.auth-shell__story-content > p:last-child {
  max-inline-size: 31rem;
  margin: var(--sys-space-6) 0 0;
  color: var(--sys-color-on-brand-muted);
  font: var(--sys-typography-body);
}

.auth-shell__signal {
  position: absolute;
  inset: 54% 8% auto auto;
  display: grid;
  inline-size: 8rem;
  block-size: 8rem;
  place-items: center;
}

.auth-shell__signal span {
  position: absolute;
  border: 1px solid rgb(255 255 255 / 16%);
  border-radius: 50%;
}

.auth-shell__signal span:nth-child(1) {
  inline-size: 3rem;
  block-size: 3rem;
}

.auth-shell__signal span:nth-child(2) {
  inline-size: 5.5rem;
  block-size: 5.5rem;
}

.auth-shell__signal span:nth-child(3) {
  inline-size: 8rem;
  block-size: 8rem;
}

.auth-shell__signal i {
  inline-size: 0.65rem;
  block-size: 0.65rem;
  border-radius: 50%;
  background: var(--sys-color-brand-highlight);
  box-shadow: 0 0 2rem var(--sys-color-brand-highlight);
}

.auth-shell__footnote {
  margin: 0;
  color: var(--sys-color-on-brand-subtle);
  font: var(--sys-typography-caption);
}

.auth-shell__panel {
  display: grid;
  min-block-size: 100dvh;
  padding: clamp(2rem, 6vw, 6rem);
  place-items: center;
}

.auth-shell__form-wrap {
  inline-size: min(100%, 32rem);
}

.auth-shell__header {
  margin-block-end: var(--sys-space-7);
}

.auth-shell__header span {
  color: var(--sys-color-text-accent);
  font: var(--sys-typography-label);
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.auth-shell__header h1 {
  margin: var(--sys-space-3) 0 var(--sys-space-3);
  font: 700 clamp(2.1rem, 4vw, 3rem) / 1.1 var(--ref-font-sans);
  letter-spacing: -0.04em;
}

.auth-shell__header p {
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-body);
}

@media (max-width: 58rem) {
  .auth-shell {
    grid-template-columns: 1fr;
  }

  .auth-shell__story {
    min-block-size: auto;
    padding: var(--sys-space-6) var(--sys-space-page);
  }

  .auth-shell__story-content,
  .auth-shell__signal,
  .auth-shell__footnote {
    display: none;
  }

  .auth-shell__panel {
    min-block-size: calc(100dvh - 5.25rem);
    align-items: start;
    padding-block: clamp(2.5rem, 8vw, 5rem);
  }
}

@media (max-width: 32rem) {
  .auth-shell__panel {
    padding-inline: var(--sys-space-5);
  }
}
</style>
