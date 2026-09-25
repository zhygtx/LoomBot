<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiError } from '@shared/api/http-client'
import { BaseButton, BaseField, BaseNotice } from '@shared/ui'

import { authApi } from '../api/auth-api'
import { useAuthOptions } from '../model/use-auth-options'
import { useSessionStore } from '../model/session-store'
import { validateEmail, validateLoginPassword } from '../model/validation'
import AuthShell from '../ui/AuthShell.vue'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const authOptions = useAuthOptions()

const form = reactive({ email: '', password: '' })
const errors = reactive({ email: '', password: '' })
const submitting = ref(false)
const submitError = ref('')

const loginEnabled = computed(() => authOptions.data.value?.loginEnabled ?? true)
const registerEnabled = computed(() => authOptions.data.value?.registerEnabled ?? true)
const passwordResetEnabled = computed(() => authOptions.data.value?.passwordResetEnabled ?? true)
const successMessage = computed(() => {
  if (route.query.registered === '1') return '注册成功，请使用邮箱和密码登录。'
  if (route.query.reset === '1') return '密码已重置，所有旧会话均已失效，请重新登录。'
  return ''
})

function validate(): boolean {
  errors.email = validateEmail(form.email)
  errors.password = validateLoginPassword(form.password)
  return !errors.email && !errors.password
}

function safeRedirect(): string {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect
  }
  return '/'
}

async function submit(): Promise<void> {
  submitError.value = ''
  if (!validate() || !loginEnabled.value) return

  submitting.value = true
  try {
    const response = await authApi.login({
      email: form.email.trim().toLowerCase(),
      password: form.password,
    })
    session.acceptLogin(response)
    await router.replace(safeRedirect())
  } catch (error) {
    submitError.value = error instanceof ApiError ? error.message : '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell
    eyebrow="Welcome back"
    title="登录 Loom"
    description="使用注册邮箱继续管理你的连接与自动化。"
  >
    <form class="auth-form" novalidate @submit.prevent="submit">
      <BaseNotice v-if="successMessage" tone="success">{{ successMessage }}</BaseNotice>
      <BaseNotice v-if="!loginEnabled" tone="warning" title="登录暂时关闭">
        管理员当前禁止签发新的登录令牌，已有会话不受影响。
      </BaseNotice>
      <BaseNotice v-if="authOptions.isError.value" tone="warning">
        暂时无法读取系统入口状态，提交时仍会由服务器进行最终校验。
      </BaseNotice>
      <BaseNotice v-if="submitError" tone="danger">{{ submitError }}</BaseNotice>

      <BaseField
        v-model="form.email"
        label="邮箱"
        name="email"
        type="email"
        inputmode="email"
        autocomplete="email"
        placeholder="name@example.com"
        :error="errors.email"
        :disabled="submitting || !loginEnabled"
        :maxlength="128"
        required
        @blur="errors.email = validateEmail(form.email)"
      />

      <BaseField
        v-model="form.password"
        label="密码"
        name="password"
        type="password"
        autocomplete="current-password"
        placeholder="请输入密码"
        :error="errors.password"
        :disabled="submitting || !loginEnabled"
        revealable
        required
        @blur="errors.password = validateLoginPassword(form.password)"
      >
        <template #label>
          <span class="auth-form__field-label">
            <span>密码</span>
            <RouterLink v-if="passwordResetEnabled" to="/forgot-password">忘记密码？</RouterLink>
          </span>
        </template>
      </BaseField>

      <BaseButton
        class="auth-form__submit"
        type="submit"
        size="large"
        :loading="submitting"
        :disabled="!loginEnabled"
      >
        登录
      </BaseButton>

      <p v-if="registerEnabled" class="auth-form__footer">
        还没有账号？<RouterLink to="/register">创建账号</RouterLink>
      </p>
    </form>
  </AuthShell>
</template>
