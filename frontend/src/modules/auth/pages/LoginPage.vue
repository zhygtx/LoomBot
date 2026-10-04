<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiError } from '@shared/api/http-client'
import { BaseButton, BaseField, BaseNotice, message } from '@shared/ui'

import { authApi } from '../api/auth-api'
import { useAuthOptions } from '../model/use-auth-options'
import { useSessionStore } from '../model/session-store'
import { validateEmail, validateLoginPassword } from '../model/validation'
import AuthShell from '../ui/AuthShell.vue'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const authOptions = useAuthOptions()

/**
 * 开发期内置账号，方便本机直接登录测试。
 *
 * <p>它由 `V1__bootstrap_schema.sql` 种出来（`admin@loombot.local` / `LoomBotAdmin123`）。
 * 只在开发构建里预填：生产构建时 `import.meta.env.DEV` 是 false，这里恒为 ''，
 * 密码不会进产物。
 *
 * <p>为什么不判断"这个账号是否真的存在"：那需要一次额外的接口往返，而登录页本来就该
 * 尽快可用。真到了没有这个账号的环境（比如清掉了种子数据），预填的值是**可以直接改掉的**，
 * 只是省一次输入，不会把人卡住。
 */
const DEV_ACCOUNT = import.meta.env.DEV
  ? { email: 'admin@loombot.local', password: 'LoomBotAdmin123' }
  : { email: '', password: '' }

const form = reactive({ email: DEV_ACCOUNT.email, password: DEV_ACCOUNT.password })
const errors = reactive({ email: '', password: '' })
const submitting = ref(false)

/** 只在开发构建、且还保持着预填值时才提示，避免用户改了之后提示还在 */
const showDevHint = computed(
  () =>
    DEV_ACCOUNT.email !== '' &&
    form.email === DEV_ACCOUNT.email &&
    form.password === DEV_ACCOUNT.password,
)

const loginEnabled = computed(() => authOptions.data.value?.loginEnabled ?? true)
const registerEnabled = computed(() => authOptions.data.value?.registerEnabled ?? true)
const passwordResetEnabled = computed(() => authOptions.data.value?.passwordResetEnabled ?? true)

/**
 * 注册成功 / 重置成功是「上一个页面交过来的结果」，用提示条报一次就够。
 *
 * 报完把 query 里的标记去掉：否则刷新一下页面又会弹一次，而那次刷新跟「刚注册完」已经没关系了。
 */
onMounted(() => {
  const registered = route.query.registered === '1'
  const reset = route.query.reset === '1'
  if (!registered && !reset) return

  message.success(
    registered
      ? '注册成功，请使用邮箱和密码登录。'
      : '密码已重置，所有旧会话均已失效，请重新登录。',
  )
  const query = { ...route.query }
  delete query.registered
  delete query.reset
  void router.replace({ query })
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
    message.error(error instanceof ApiError ? error.message : '登录失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell
    eyebrow="Welcome back"
    title="登录 LoomBot"
    description="使用注册邮箱继续管理你的连接与自动化。"
  >
    <form class="auth-form" novalidate @submit.prevent="submit">
      <BaseNotice v-if="!loginEnabled" tone="warning" title="登录暂时关闭">
        管理员当前禁止签发新的登录令牌，已有会话不受影响。
      </BaseNotice>
      <BaseNotice v-if="authOptions.isError.value" tone="warning">
        暂时无法读取系统入口状态，提交时仍会由服务器进行最终校验。
      </BaseNotice>

      <!-- 开发构建才有：说明这里的账号密码是从哪来的，免得以为是真账号 -->
      <BaseNotice v-if="showDevHint" tone="info" title="开发账号已预填">
        来自数据库种子数据，改掉即可用自己的账号。生产构建不会预填。
      </BaseNotice>

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
        <template #label-extra>
          <RouterLink v-if="passwordResetEnabled" to="/forgot-password">忘记密码？</RouterLink>
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
