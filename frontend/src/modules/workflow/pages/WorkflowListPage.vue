<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { ApiError } from '@shared/api/http-client'
import { hasPermission } from '@shared/session/permissions'
import { BaseButton, message } from '@shared/ui'

import { useSessionStore } from '@modules/auth/model/session-store'
import {
  deleteWorkflow,
  listWorkflows,
  setWorkflowEnabled,
  type WorkflowSummary,
} from '../api/workflow-api'

const workflows = ref<WorkflowSummary[]>([])
const loading = ref(true)
const busyId = ref<string | null>(null)
const session = useSessionStore()
const router = useRouter()

const can = (permission: string) => hasPermission(session.user?.permissions, permission)

const load = async () => {
  loading.value = true
  try {
    workflows.value = await listWorkflows()
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '工作流列表加载失败')
  } finally {
    loading.value = false
  }
}

const withBusy = async (id: string, action: () => Promise<void>) => {
  busyId.value = id
  try {
    await action()
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '操作失败')
  } finally {
    busyId.value = null
  }
}

const toggle = (item: WorkflowSummary) =>
  withBusy(item.id, async () => {
    await setWorkflowEnabled(item.id, item.enabled !== 1)
    message.success(item.enabled === 1 ? '已停用' : '已启用')
    await load()
  })

const remove = (item: WorkflowSummary) =>
  withBusy(item.id, async () => {
    if (!window.confirm(`确认删除工作流「${item.name}」？`)) return
    await deleteWorkflow(item.id)
    message.success('已删除')
    await load()
  })

onMounted(load)
</script>

<template>
  <section class="workflow-page">
    <header class="workflow-page__head">
      <div>
        <h1>工作流</h1>
        <p>按 DAG 编排事件、动作与普通节点，保存即产生新版本。</p>
      </div>
      <BaseButton v-if="can('workflow:def:save')" @click="router.push('/workflow/edit')">
        新建工作流
      </BaseButton>
    </header>

    <p v-if="loading" class="workflow-page__empty">正在加载…</p>
    <p v-else-if="!workflows.length" class="workflow-page__empty">还没有工作流。</p>

    <ul v-else class="workflow-cards">
      <li v-for="item in workflows" :key="item.id" class="workflow-card">
        <div class="workflow-card__main">
          <div class="workflow-card__title">
            <strong>{{ item.name }}</strong>
            <span class="workflow-tag" :class="{ 'is-on': item.enabled === 1 }">
              {{ item.enabled === 1 ? '已启用' : '已停用' }}
            </span>
            <span v-if="item.hasAlert" class="workflow-tag is-alert">
              插件节点已变更
            </span>
          </div>
          <p class="workflow-card__desc">{{ item.description || '没有描述' }}</p>
        </div>
        <div class="workflow-card__actions">
          <BaseButton
            v-if="can('workflow:def:save')"
            appearance="secondary"
            size="small"
            @click="router.push(`/workflow/edit/${item.id}`)"
          >
            编辑
          </BaseButton>
          <BaseButton
            v-if="can('workflow:def:update')"
            appearance="secondary"
            size="small"
            :disabled="busyId === item.id"
            @click="toggle(item)"
          >
            {{ item.enabled === 1 ? '停用' : '启用' }}
          </BaseButton>
          <BaseButton
            v-if="can('workflow:def:delete')"
            appearance="danger"
            size="small"
            :disabled="busyId === item.id"
            @click="remove(item)"
          >
            删除
          </BaseButton>
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.workflow-page {
  padding: 24px;
}

.workflow-page__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.workflow-page__head h1 {
  margin: 0 0 6px;
  font-size: 20px;
}

.workflow-page__head p {
  margin: 0;
  font-size: 13px;
  opacity: 0.7;
}

.workflow-page__empty {
  padding: 48px 0;
  text-align: center;
  font-size: 13px;
  opacity: 0.7;
}

.workflow-cards {
  display: grid;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.workflow-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px;
  border: 1px solid var(--loombot-border-color, rgba(0, 0, 0, 0.12));
  border-radius: 10px;
}

.workflow-card__title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.workflow-card__desc {
  margin: 6px 0 0;
  font-size: 13px;
  opacity: 0.7;
}

.workflow-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.workflow-card__actions button {
  padding: 6px 14px;
  border: 1px solid var(--loombot-border-color, rgba(0, 0, 0, 0.12));
  border-radius: 6px;
  background: transparent;
  color: inherit;
  font-size: 13px;
  cursor: pointer;
}

.workflow-card__actions button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.workflow-card__actions button.is-danger:hover {
  border-color: var(--loombot-danger-color, #d64545);
  color: var(--loombot-danger-color, #d64545);
}

.workflow-tag {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
  background: var(--loombot-fill-color, rgba(0, 0, 0, 0.06));
}

.workflow-tag.is-on {
  background: var(--loombot-success-bg, rgba(22, 163, 74, 0.12));
  color: var(--loombot-success-color, #16a34a);
}

.workflow-tag.is-alert {
  background: color-mix(in srgb, var(--sys-color-warning-text) 14%, transparent);
  color: var(--sys-color-warning-text);
}
</style>
