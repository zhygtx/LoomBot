<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Pencil, Trash2, TriangleAlert, Upload, X } from '@lucide/vue'

import { ApiError } from '@shared/api/http-client'
import { hasPermission } from '@shared/session/permissions'
import { BaseButton, BaseField, BaseNotice, BaseSurface, message } from '@shared/ui'

import { useSessionStore } from '../../auth/model/session-store'
import { systemApi } from '../api/system-api'
import type { PluginRepo, PluginRepoPayload } from '../model/types'

/**
 * 插件库管理。
 *
 * 后端写/删的就是 `python/plugins/<库>/` 那套文件夹，所以这里只是"遥控器"：
 * 手工丢进去的库照样有效，两条路不打架。
 *
 * 删除库**不级联删插件** —— 插件保留、工作流侧标不可用，所以文案要说清楚，
 * 别让人以为点一下插件就没了。
 */

const session = useSessionStore()
const repos = ref<PluginRepo[]>([])
const isLoading = ref(true)
const isRefreshing = ref(false)
const isSaving = ref(false)
const loadFailed = ref(false)
/** 行内二次确认，替代 window.confirm，和菜单管理页保持一致。 */
const confirmDeleteKey = ref<string | null>(null)
const uploadingKey = ref<string | null>(null)
/** 正在上传的目标库；文件选择框弹出前记下来，选完文件才能知道发给谁。 */
const uploadTarget = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const form = ref<{ key: string } & PluginRepoPayload>({
  key: '',
  url: '',
  branch: 'main',
  scanner: 'loom',
})
/** 非空表示正在编辑已有库，此时 key 不给改（它是身份）。 */
const editingKey = ref<string | null>(null)
const formError = ref('')

const canList = computed(() => hasPermission(session.user?.permissions, 'plugin:repo:list'))
const canManage = computed(() => hasPermission(session.user?.permissions, 'plugin:repo:manage'))

/** 和后端同一条规则：key 同时当文件夹名，限死字符集顺带挡住路径穿越。 */
const KEY_PATTERN = /^[A-Za-z0-9._-]{1,64}$/

function resetForm(): void {
  editingKey.value = null
  form.value = { key: '', url: '', branch: 'main', scanner: 'loom' }
  formError.value = ''
}

function startEdit(repo: PluginRepo): void {
  if (!repo.key) return
  editingKey.value = repo.key
  form.value = {
    key: repo.key,
    url: repo.url ?? '',
    branch: repo.branch ?? 'main',
    scanner: repo.scanner ?? 'loom',
  }
  formError.value = ''
}

function formatTime(value: string | null): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

/** 库的身份标签：能解析的显示 key，解析不出来的显示文件夹名。 */
function titleOf(repo: PluginRepo): string {
  return repo.key ?? repo.folder ?? '（未命名）'
}

function reasonOf(repo: PluginRepo): string {
  return repo.error ?? '库声明无法解析，同步时会跳过这个库'
}

async function load(): Promise<void> {
  isRefreshing.value = true
  try {
    repos.value = await systemApi.listPluginRepos()
    loadFailed.value = false
  } catch (error) {
    loadFailed.value = true
    message.error(error instanceof ApiError ? error.message : '插件库列表加载失败，请稍后重试')
  } finally {
    isLoading.value = false
    isRefreshing.value = false
  }
}

async function submit(): Promise<void> {
  const key = form.value.key.trim()
  if (!KEY_PATTERN.test(key)) {
    formError.value = '只能包含字母、数字、点、下划线和短横线，且不超过 64 个字符'
    return
  }
  formError.value = ''
  isSaving.value = true
  const editing = editingKey.value !== null
  try {
    await systemApi.savePluginRepo(key, {
      url: form.value.url.trim(),
      branch: form.value.branch.trim() || 'main',
      scanner: form.value.scanner,
    })
    message.success(editing ? `已更新插件库「${key}」` : `已创建插件库「${key}」，正在同步`)
    resetForm()
    await load()
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '插件库保存失败，请稍后重试')
  } finally {
    isSaving.value = false
  }
}

function pickFileFor(repo: PluginRepo): void {
  if (!repo.key) return
  uploadTarget.value = repo.key
  fileInput.value?.click()
}

async function onFileChosen(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 清空才能连续选同一个文件两次（第二次 change 不会触发）
  input.value = ''
  const key = uploadTarget.value
  uploadTarget.value = null
  if (!file || !key) return
  uploadingKey.value = key
  try {
    await systemApi.uploadPluginRepoFiles(key, file)
    message.success(`已上传 ${file.name}，正在同步`)
    await load()
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '配套文件上传失败，请稍后重试')
  } finally {
    uploadingKey.value = null
  }
}

async function remove(repo: PluginRepo): Promise<void> {
  const key = repo.key
  if (!key) return
  try {
    await systemApi.deletePluginRepo(key)
    confirmDeleteKey.value = null
    message.success(`已删除插件库「${key}」，相关插件保留并标为不可用`)
    await load()
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '插件库删除失败，请稍后重试')
  }
}

async function syncNow(): Promise<void> {
  try {
    await systemApi.syncPluginRepos()
    message.success('已触发同步，稍后刷新看结果')
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '触发同步失败，请稍后重试')
  }
}

onMounted(load)
</script>

<template>
  <section class="repo-panel">
    <header class="repo-panel__head">
      <div>
        <span class="repo-panel__kicker">Plugin / Repository</span>
        <h2>插件库</h2>
        <p>
          加一个库就是放一个文件夹。这里写的就是那个文件夹：声明（repo.json）由表单生成， 子扫描器和
          SDK 兼容层这类配套文件用上传带上。
        </p>
      </div>
      <div class="repo-panel__head-actions">
        <span class="repo-panel__count">{{ repos.length }} 个</span>
        <BaseButton appearance="secondary" size="small" :loading="isRefreshing" @click="load">
          刷新
        </BaseButton>
        <BaseButton v-if="canManage" appearance="ghost" size="small" @click="syncNow">
          立即同步
        </BaseButton>
      </div>
    </header>

    <BaseNotice v-if="!canList" tone="warning" title="没有权限">
      当前用户没有查看插件库的权限。
    </BaseNotice>

    <BaseSurface v-else-if="isLoading" padding="large">
      <p class="repo-panel__empty">正在读取插件库…</p>
    </BaseSurface>

    <BaseSurface v-else-if="loadFailed" padding="large">
      <p class="repo-panel__empty">插件库列表没能加载出来。</p>
      <div class="repo-panel__retry">
        <BaseButton size="small" :loading="isRefreshing" @click="load">重试</BaseButton>
      </div>
    </BaseSurface>

    <template v-else>
      <BaseNotice v-if="!canManage" tone="info" title="只读">
        当前用户只能查看插件库。新增、上传和删除需要 <code>plugin:repo:manage</code> 权限。
      </BaseNotice>

      <BaseSurface v-if="canManage" padding="large" class="repo-panel__form">
        <div class="repo-panel__form-head">
          <strong>{{ editingKey ? `编辑「${editingKey}」` : '新增插件库' }}</strong>
          <BaseButton v-if="editingKey" appearance="ghost" size="small" @click="resetForm">
            <X :size="14" aria-hidden="true" />
            取消编辑
          </BaseButton>
        </div>
        <div class="repo-panel__grid">
          <BaseField
            v-model="form.key"
            name="plugin-repo-key"
            label="库标识"
            :disabled="editingKey !== null"
            placeholder="例如 generalbot-market"
            :error="formError"
            hint="同时作为文件夹名；一旦创建就是稳定身份，不要改。"
          />
          <BaseField
            v-model="form.url"
            name="plugin-repo-url"
            label="Git 地址"
            placeholder="留空表示纯本地库，不做 clone / pull"
          />
          <BaseField v-model="form.branch" name="plugin-repo-branch" label="分支" />
          <div class="repo-panel__select">
            <label class="repo-panel__select-label" for="plugin-repo-scanner">子扫描器</label>
            <select
              id="plugin-repo-scanner"
              v-model="form.scanner"
              class="repo-panel__select-input"
            >
              <option value="loom">loom（Loom 自己的格式）</option>
              <option value="local">local（用库文件夹里的 scanner.py）</option>
            </select>
            <span class="repo-panel__select-hint">
              选 local 就要把 scanner.py 一并上传，否则同步时会报"没有实现 load_nodes()"。
            </span>
          </div>
        </div>
        <div class="repo-panel__form-actions">
          <BaseButton size="small" :loading="isSaving" @click="submit">
            {{ editingKey ? '保存声明' : '创建插件库' }}
          </BaseButton>
        </div>
      </BaseSurface>

      <BaseSurface v-if="!repos.length" padding="large">
        <p class="repo-panel__empty">还没有任何插件库。</p>
      </BaseSurface>

      <BaseSurface v-else padding="none">
        <ul class="repo-list">
          <li
            v-for="repo in repos"
            :key="repo.key ?? repo.folder ?? 'unknown'"
            class="repo-row"
            :class="{ 'repo-row--invalid': !repo.valid }"
          >
            <div class="repo-row__copy">
              <div class="repo-row__title">
                <strong>{{ titleOf(repo) }}</strong>
                <span v-if="repo.scanner" class="repo-row__tag">{{ repo.scanner }}</span>
                <span v-if="!repo.valid" class="repo-row__tag repo-row__tag--warn">不可用</span>
              </div>
              <p class="repo-row__meta">
                <span v-if="repo.url">{{ repo.url }}</span>
                <span v-else>本地库</span>
                <span v-if="repo.branch">· {{ repo.branch }}</span>
                <span v-if="repo.folder">· 文件夹 {{ repo.folder }}</span>
              </p>
              <p v-if="!repo.valid" class="repo-row__note repo-row__note--warn">
                <TriangleAlert :size="14" aria-hidden="true" />
                {{ reasonOf(repo) }}
              </p>
              <p v-else-if="repo.lastError" class="repo-row__note repo-row__note--warn">
                <TriangleAlert :size="14" aria-hidden="true" />
                上次同步失败：{{ repo.lastError }}
              </p>
              <p v-else class="repo-row__note">
                上次扫描 {{ formatTime(repo.lastScanTime) }} · 上次拉取
                {{ formatTime(repo.lastPullTime) }}
              </p>
            </div>

            <div v-if="canManage" class="repo-row__actions">
              <template v-if="confirmDeleteKey === repo.key">
                <span class="repo-row__confirm">插件会保留但标为不可用，确认删除？</span>
                <BaseButton appearance="danger" size="small" @click="remove(repo)">
                  确认删除
                </BaseButton>
                <BaseButton appearance="ghost" size="small" @click="confirmDeleteKey = null">
                  取消
                </BaseButton>
              </template>
              <template v-else>
                <BaseButton
                  appearance="secondary"
                  size="small"
                  :disabled="!repo.key"
                  @click="startEdit(repo)"
                >
                  <Pencil :size="14" aria-hidden="true" />
                  编辑
                </BaseButton>
                <BaseButton
                  appearance="secondary"
                  size="small"
                  :disabled="!repo.key"
                  :loading="uploadingKey === repo.key"
                  @click="pickFileFor(repo)"
                >
                  <Upload :size="14" aria-hidden="true" />
                  上传配套文件
                </BaseButton>
                <BaseButton
                  appearance="danger"
                  size="small"
                  :disabled="!repo.key"
                  @click="confirmDeleteKey = repo.key"
                >
                  <Trash2 :size="14" aria-hidden="true" />
                  删除
                </BaseButton>
              </template>
            </div>
          </li>
        </ul>
      </BaseSurface>
    </template>

    <!-- 视觉上藏起来但留在 DOM 里：上传按钮点它来弹文件选择框 -->
    <input
      ref="fileInput"
      class="repo-panel__file"
      type="file"
      accept=".zip,.py"
      @change="onFileChosen"
    />
  </section>
</template>

<style scoped>
.repo-panel {
  margin-block-start: var(--sys-space-7);
}

.repo-panel__head {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  justify-content: space-between;
  gap: var(--sys-space-4);
  margin-block-end: var(--sys-space-3);
}

.repo-panel__kicker {
  color: var(--sys-color-text-accent);
  font: var(--sys-typography-label);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.repo-panel__head h2 {
  margin: var(--sys-space-1) 0 0;
  font: 700 1.2rem/1.2 var(--ref-font-sans);
}

.repo-panel__head p {
  max-inline-size: 46rem;
  margin: var(--sys-space-2) 0 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.repo-panel__head-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sys-space-3);
}

.repo-panel__count {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  white-space: nowrap;
}

.repo-panel__form {
  margin-block-end: var(--sys-space-4);
}

.repo-panel__form-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sys-space-3);
  margin-block-end: var(--sys-space-4);
}

.repo-panel__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr));
  gap: var(--sys-space-4);
}

.repo-panel__select {
  display: grid;
  gap: var(--sys-space-2);
  align-content: start;
}

.repo-panel__select-label {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-label);
}

.repo-panel__select-input {
  min-block-size: 2.5rem;
  border: var(--cmp-field-border-width) solid var(--sys-color-border-strong);
  border-radius: var(--cmp-field-radius);
  padding-inline: var(--sys-space-3);
  background: var(--sys-color-field);
  color: var(--sys-color-text);
  font: var(--sys-typography-body-compact);
  outline: 0;
}

.repo-panel__select-input:focus {
  border-color: var(--sys-color-action-primary);
  box-shadow: 0 0 0 var(--sys-focus-width) var(--sys-color-focus);
}

.repo-panel__select-hint {
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.repo-panel__form-actions {
  display: flex;
  justify-content: flex-end;
  margin-block-start: var(--sys-space-4);
}

.repo-panel__empty {
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-body-compact);
  text-align: center;
}

.repo-panel__retry {
  display: flex;
  justify-content: center;
  margin-block-start: var(--sys-space-3);
}

.repo-panel__file {
  position: absolute;
  inline-size: 1px;
  block-size: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

.repo-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.repo-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--sys-space-4);
  padding: var(--sys-space-4) var(--sys-space-5);
  border-block-end: 1px solid var(--sys-color-border);
}

.repo-list > .repo-row:last-child {
  border-block-end: 0;
}

.repo-row--invalid {
  background: var(--sys-color-warning-subtle);
}

.repo-row__copy {
  min-inline-size: 0;
  flex: 1 1 20rem;
}

.repo-row__title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sys-space-2);
}

.repo-row__title strong {
  font: var(--sys-typography-label-strong);
  font-size: 0.9375rem;
  overflow-wrap: anywhere;
}

.repo-row__tag {
  border-radius: var(--ref-radius-round);
  padding-inline: var(--sys-space-2);
  background: var(--sys-color-surface-muted);
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  font-size: 0.75rem;
}

.repo-row__tag--warn {
  background: var(--sys-color-warning-border, var(--sys-color-warning-subtle));
  color: var(--sys-color-warning-text);
}

.repo-row__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sys-space-2);
  margin: var(--sys-space-1) 0 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
  overflow-wrap: anywhere;
}

.repo-row__note {
  display: flex;
  align-items: start;
  gap: var(--sys-space-2);
  margin: var(--sys-space-2) 0 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.repo-row__note--warn {
  color: var(--sys-color-warning-text);
}

.repo-row__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sys-space-2);
}

.repo-row__confirm {
  color: var(--sys-color-danger-text);
  font: var(--sys-typography-caption);
}

@media (max-width: 60rem) {
  .repo-row {
    align-items: flex-start;
    flex-direction: column;
  }

  .repo-row__actions {
    justify-content: flex-start;
  }
}
</style>
