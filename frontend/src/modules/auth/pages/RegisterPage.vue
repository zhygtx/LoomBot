<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { ApiError } from '@shared/api/http-client'
import { BaseButton, BaseField, BaseNotice, message } from '@shared/ui'

import { authApi } from '../api/auth-api'
import { useAuthOptions } from '../model/use-auth-options'
import { useCodeCountdown } from '../model/use-code-countdown'
import { validateCode, validateEmail, validatePassword } from '../model/validation'
import AuthShell from '../ui/AuthShell.vue'

const router = useRouter()
const authOptions = useAuthOptions()
const countdown = useCodeCountdown()

const form = reactive({
  email: '',
  code: '',
  password: '',
  confirmPassword: '',
})
const errors = reactive({
  email: '',
  code: '',
  password: '',
  confirmPassword: '',
})
const submitting = ref(false)
const sendingCode = ref(false)

const registerEnabled = computed(() => authOptions.data.value?.registerEnabled ?? true)
const emailCodeEnabled = computed(() => authOptions.data.value?.emailCodeEnabled ?? true)
const formDisabled = computed(() => submitting.value || !registerEnabled.value)
const canSendCode = computed(
  () => registerEnabled.value && emailCodeEnabled.value && countdown.seconds.value === 0,
)

function validateConfirmPassword(): string {
  if (!form.confirmPassword) return '请再次输入密码'
  return form.confirmPassword === form.password ? '' : '两次输入的密码不一致'
}

function validate(): boolean {
  errors.email = validateEmail(form.email)
  errors.code = validateCode(form.code)
  errors.password = validatePassword(form.password)
  errors.confirmPassword = validateConfirmPassword()
  return Object.values(errors).every((value) => !value)
}

async function sendCode(): Promise<void> {
  errors.email = validateEmail(form.email)
  if (errors.email || !canSendCode.value) return

  sendingCode.value = true
  try {
    await authApi.sendEmailCode({
      email: form.email.trim().toLowerCase(),
      scene: 'REGISTER',
    })
    message.success('验证码已发送，请检查邮箱。')
    countdown.start()
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '验证码发送失败，请稍后重试')
  } finally {
    sendingCode.value = false
  }
}

async function submit(): Promise<void> {
  if (!validate() || !registerEnabled.value) return

  submitting.value = true
  try {
    await authApi.register({
      email: form.email.trim().toLowerCase(),
      code: form.code,
      password: form.password,
    })
    await router.replace({ path: '/login', query: { registered: '1' } })
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '注册失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell
    eyebrow="Join LoomBot"
    title="创建账号"
    description="使用邮箱验证码创建账号，注册后即可进入控制台。"
  >
    <form class="auth-form" novalidate @submit.prevent="submit">
      <BaseNotice v-if="!registerEnabled" tone="warning" title="注册暂时关闭">
        管理员当前不允许创建新账号。
      </BaseNotice>
      <BaseNotice v-else-if="!emailCodeEnabled" tone="warning" title="验证码服务暂时关闭">
        当前无法发送注册验证码，因此暂时不能完成注册。
      </BaseNotice>
      <BaseNotice v-if="authOptions.isError.value" tone="warning">
        暂时无法读取系统入口状态，提交时仍会由服务器进行最终校验。
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
        :disabled="formDisabled"
        :maxlength="128"
        required
        @blur="errors.email = validateEmail(form.email)"
      />

      <BaseField
        v-model="form.code"
        label="邮箱验证码"
        name="code"
        inputmode="numeric"
        autocomplete="one-time-code"
        placeholder="6 位数字"
        :error="errors.code"
        :disabled="formDisabled"
        :maxlength="6"
        required
        @blur="errors.code = validateCode(form.code)"
      >
        <template #suffix>
          <BaseButton
            class="auth-form__code-button"
            appearance="ghost"
            size="small"
            :loading="sendingCode"
            :disabled="!canSendCode"
            @click="sendCode"
          >
            {{ countdown.seconds.value > 0 ? `${countdown.seconds.value}s` : '发送验证码' }}
          </BaseButton>
        </template>
      </BaseField>

      <div class="auth-form__grid">
        <BaseField
          v-model="form.password"
          label="密码"
          name="password"
          type="password"
          autocomplete="new-password"
          placeholder="6~20 位"
          :error="errors.password"
          :disabled="formDisabled"
          :maxlength="20"
          revealable
          required
          @blur="errors.password = validatePassword(form.password)"
        />
        <BaseField
          v-model="form.confirmPassword"
          label="确认密码"
          name="confirm-password"
          type="password"
          autocomplete="new-password"
          placeholder="再次输入"
          :error="errors.confirmPassword"
          :disabled="formDisabled"
          :maxlength="20"
          revealable
          required
          @blur="errors.confirmPassword = validateConfirmPassword()"
        />
      </div>

      <BaseButton
        class="auth-form__submit"
        type="submit"
        size="large"
        :loading="submitting"
        :disabled="!registerEnabled || !emailCodeEnabled"
      >
        创建账号
      </BaseButton>

      <p class="auth-form__footer">已有账号？<RouterLink to="/login">返回登录</RouterLink></p>
    </form>
  </AuthShell>
</template>
