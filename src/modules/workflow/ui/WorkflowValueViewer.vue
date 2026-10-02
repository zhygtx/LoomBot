<script setup lang="ts">
import { computed, ref } from 'vue'
import { Copy, Download, X } from '@lucide/vue'

import { ApiError } from '@shared/api/http-client'
import { readStoredToken } from '@shared/session/storage'
import { BaseButton, message } from '@shared/ui'

import { executionFileUrl, getExecutionPayload } from '../api/workflow-api'
import { asValueMarker, formatBytes, formatRawContent, formatValue } from '../model/format'

const props = defineProps<{
  executionId: string
  value: unknown
  /** 画布上节点详情用的紧凑预览：矮一点，点开仍是完整数据窗口。 */
  compact?: boolean
  /** 预览框右下角的小灰字提示，例如「点击查看」。 */
  hint?: string
}>()

const marker = computed(() => asValueMarker(props.value))
const textRef = computed(() =>
  marker.value?.ref && marker.value.kind !== 'file' ? marker.value.ref : '',
)
const isFile = computed(() => marker.value?.kind === 'file')
const omitted = computed(() => Boolean(marker.value?.contentOmitted))

const loading = ref(false)
const modalOpen = ref(false)
const modalTitle = ref('')
const modalContent = ref('')

/** 大内容正文不在详情里，点开才请求。 */
async function openPayload(): Promise<void> {
  if (!textRef.value) return
  loading.value = true
  try {
    // 后端存的是紧凑 JSON，这里先解析再美化，否则整段挤在一行看不出来结构
    modalContent.value = formatRawContent(
      await getExecutionPayload(props.executionId, textRef.value),
    )
    modalTitle.value = `大内容 ${textRef.value}（${formatBytes(marker.value?.size)}）`
    modalOpen.value = true
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '内容加载失败')
  } finally {
    loading.value = false
  }
}

/** 二进制/文件走下载：带认证头取回 Blob，再触发浏览器下载。 */
async function downloadFile(): Promise<void> {
  const fileName = marker.value?.fileName
  if (!fileName) return
  loading.value = true
  try {
    const token = readStoredToken()
    const response = await fetch(executionFileUrl(props.executionId, fileName), {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (error) {
    message.error(error instanceof Error ? `文件下载失败：${error.message}` : '文件下载失败')
  } finally {
    loading.value = false
  }
}

function openInline(): void {
  modalTitle.value = '完整内容'
  modalContent.value = formatValue(props.value)
  modalOpen.value = true
}

async function copyContent(): Promise<void> {
  try {
    await navigator.clipboard.writeText(modalContent.value)
    message.success('已复制')
  } catch {
    message.error('复制失败')
  }
}

</script>

<template>
  <div class="value-viewer">
    <button
      v-if="textRef"
      type="button"
      class="value-viewer__action"
      :disabled="loading"
      @click="openPayload"
    >
      {{ loading ? '加载中…' : `数据过大（${formatBytes(marker?.size)}），点击查看` }}
    </button>

    <button
      v-else-if="isFile"
      type="button"
      class="value-viewer__action is-file"
      :disabled="loading"
      @click="downloadFile"
    >
      <Download :size="14" />
      下载 {{ marker?.fileName || '文件' }}（{{ formatBytes(marker?.size) }}）
    </button>

    <p v-else-if="omitted" class="value-viewer__omitted">
      内容超过单条上限未保存（{{ formatBytes(marker?.size) }}）
    </p>

    <div v-else class="value-viewer__preview">
      <pre
        class="value-viewer__inline"
        :class="{ 'is-compact': compact }"
        title="点击查看完整内容"
        @click="openInline"
      >{{ formatValue(value) }}</pre>
      <span v-if="hint" class="value-viewer__hint">{{ hint }}</span>
    </div>

    <!--
      必须 teleport 到 body：画布 workflow-world 带 transform，
      position: fixed 会被它当成包含块，导致弹窗跟着画布一起缩放、被裁切。
    -->
    <Teleport to="body">
      <div v-if="modalOpen" class="value-viewer__overlay" @click.self="modalOpen = false">
        <section class="value-viewer__modal" role="dialog" aria-modal="true">
          <header>
            <strong>{{ modalTitle }}</strong>
            <div class="value-viewer__modal-actions">
              <BaseButton appearance="secondary" size="small" @click="copyContent">
                <Copy :size="14" />
                复制
              </BaseButton>
              <button
                type="button"
                class="value-viewer__close"
                aria-label="关闭"
                @click="modalOpen = false"
              >
                <X :size="18" />
              </button>
            </div>
          </header>
          <pre>{{ modalContent }}</pre>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.value-viewer {
  display: grid;
  gap: var(--sys-space-2);
}

.value-viewer__action {
  display: inline-flex;
  align-items: center;
  justify-self: start;
  gap: 0.35rem;
  border: 1px dashed var(--sys-color-border-strong);
  border-radius: 0.5rem;
  background: var(--sys-color-field);
  color: var(--sys-color-action-primary);
  cursor: pointer;
  font: var(--sys-typography-label);
  padding: 0.35rem 0.7rem;
}

.value-viewer__action.is-file {
  color: var(--sys-color-warning-text);
}

.value-viewer__action:disabled {
  cursor: progress;
  opacity: 0.7;
}

.value-viewer__omitted {
  margin: 0;
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-caption);
}

.value-viewer__inline {
  overflow: auto;
  max-block-size: 12rem;
  margin: 0;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.5rem;
  background: var(--sys-color-field);
  color: var(--sys-color-text);
  cursor: pointer;
  font: 0.75rem/1.5 var(--ref-font-mono);
  padding: var(--sys-space-3);
  white-space: pre-wrap;
  word-break: break-word;
}

.value-viewer__inline.is-compact {
  max-block-size: 6rem;
}

.value-viewer__preview {
  position: relative;
  display: grid;
  min-inline-size: 0;
}

/*
 * 提示悬浮在数据框右下角，不占布局空间。
 * 只留文字、不加底色块，免得盖住内容；用同色描边保证压在文字上也读得清。
 */
.value-viewer__hint {
  position: absolute;
  inset-block-end: 0.3rem;
  inset-inline-end: 0.45rem;
  color: var(--sys-color-text-subtle);
  font: var(--sys-typography-caption);
  pointer-events: none;
  text-shadow:
    0 0 0.25rem var(--sys-color-field),
    0 0 0.25rem var(--sys-color-field);
}

.value-viewer__overlay {
  position: fixed;
  z-index: 1300;
  inset: 0;
  display: grid;
  place-items: center;
  background: color-mix(in srgb, var(--sys-color-text) 32%, transparent);
  padding: var(--sys-space-4);
}

.value-viewer__modal {
  display: flex;
  inline-size: min(64rem, 100%);
  max-block-size: min(48rem, calc(100dvh - 2rem));
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--sys-color-border);
  border-radius: 0.85rem;
  background: var(--sys-color-surface-raised);
  box-shadow: var(--sys-shadow-raised);
}

.value-viewer__modal header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-block-end: 1px solid var(--sys-color-border);
  gap: var(--sys-space-3);
  padding: var(--sys-space-3) var(--sys-space-4);
}

.value-viewer__modal header div {
  display: flex;
  align-items: center;
  gap: var(--sys-space-2);
}

.value-viewer__close {
  display: grid;
  inline-size: 2rem;
  block-size: 2rem;
  place-items: center;
  flex: none;
  border: 1px solid var(--sys-color-border);
  border-radius: 50%;
  background: var(--sys-color-field);
  color: var(--sys-color-text-muted);
  cursor: pointer;
}

.value-viewer__close:hover {
  background: var(--sys-color-action-subtle-hover);
  color: var(--sys-color-text);
}

.value-viewer__modal pre {
  flex: 1;
  overflow: auto;
  margin: 0;
  color: var(--sys-color-text);
  font: 0.75rem/1.55 var(--ref-font-mono);
  padding: var(--sys-space-4);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
