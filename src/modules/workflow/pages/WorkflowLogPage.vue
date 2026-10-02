<script setup lang="ts">
import WorkflowExecutionLogPanel from '../ui/WorkflowExecutionLogPanel.vue'
</script>

<template>
  <section class="workflow-log">
    <header class="workflow-log__head">
      <div>
        <h1>执行日志</h1>
        <p>每次执行的触发来源、节点过程和结果。保留天数可在系统配置里调整。</p>
      </div>
    </header>

    <!-- 列表、筛选、内容搜索和展开详情都在面板里，编辑器抽屉复用同一个组件 -->
    <WorkflowExecutionLogPanel />
  </section>
</template>

<style scoped>
.workflow-log {
  display: grid;
  align-content: start;
  gap: var(--sys-space-4);
  padding: var(--sys-space-5);
}

.workflow-log__head h1 {
  margin: 0 0 0.35rem;
  font-size: 20px;
}

.workflow-log__head p {
  margin: 0;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}
</style>
