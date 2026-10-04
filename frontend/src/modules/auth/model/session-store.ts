import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { clearStoredSession, readStoredSession, writeStoredSession } from '@shared/session/storage'

import { authApi } from '../api/auth-api'
import type { LoginResponse, UserProfile } from './types'

export const useSessionStore = defineStore('session', () => {
  const stored = readStoredSession()
  const token = ref(stored?.token ?? null)
  const tokenType = ref(stored?.tokenType ?? 'Bearer')
  const expiresAt = ref(stored?.expiresAt ?? null)
  const user = ref<UserProfile | null>(null)

  const isAuthenticated = computed(() => Boolean(token.value && expiresAt.value))

  function acceptLogin(response: LoginResponse): void {
    const nextExpiresAt = Date.now() + response.expiresIn * 1000
    token.value = response.token
    tokenType.value = response.tokenType
    expiresAt.value = nextExpiresAt
    user.value = response.user
    writeStoredSession({
      token: response.token,
      tokenType: response.tokenType,
      expiresAt: nextExpiresAt,
    })
  }

  async function loadProfile(): Promise<UserProfile> {
    const profile = await authApi.profile()
    user.value = profile
    return profile
  }

  function clear(): void {
    token.value = null
    tokenType.value = 'Bearer'
    expiresAt.value = null
    user.value = null
    clearStoredSession()
  }

  return { token, tokenType, expiresAt, user, isAuthenticated, acceptLogin, loadProfile, clear }
})
