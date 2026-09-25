const SESSION_STORAGE_KEY = 'loom.auth.session'

export interface StoredSession {
  token: string
  tokenType: string
  expiresAt: number
}

export function readStoredSession(): StoredSession | null {
  const raw = localStorage.getItem(SESSION_STORAGE_KEY)
  if (!raw) return null

  try {
    const session = JSON.parse(raw) as Partial<StoredSession>
    if (!session.token || !session.tokenType || !session.expiresAt) {
      clearStoredSession()
      return null
    }
    if (session.expiresAt <= Date.now()) {
      clearStoredSession()
      return null
    }
    return session as StoredSession
  } catch {
    clearStoredSession()
    return null
  }
}

export function readStoredToken(): string | null {
  return readStoredSession()?.token ?? null
}

export function writeStoredSession(session: StoredSession): void {
  localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session))
}

export function clearStoredSession(): void {
  localStorage.removeItem(SESSION_STORAGE_KEY)
  // 清理旧前端可能留下的认证数据，避免迁移后产生假登录状态。
  ;['token', 'userId', 'account', 'email', 'name'].forEach((key) => localStorage.removeItem(key))
}
