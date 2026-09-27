import axios, { type AxiosRequestConfig } from 'axios'

import { clearStoredSession, readStoredToken } from '@shared/session/storage'

export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  traceId: string
  timestamp: number
}

export class ApiError extends Error {
  readonly code?: number
  readonly status?: number
  readonly traceId?: string

  constructor(message: string, options?: { code?: number; status?: number; traceId?: string }) {
    super(message)
    this.name = 'ApiError'
    this.code = options?.code
    this.status = options?.status
    this.traceId = options?.traceId
  }
}

export const httpClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 15_000,
  headers: {
    Accept: 'application/json',
  },
})

const NUMERIC_RESPONSE_FIELDS = new Set([
  'expiresIn',
  'timestamp',
  'total',
  'pageNum',
  'pageSize',
  'pages',
])

function normalizeProtocolIds(
  value: unknown,
  direction: 'request' | 'response',
  key?: string,
): unknown {
  if (value === null || value === undefined) return value
  if (Array.isArray(value)) return value.map((item) => normalizeProtocolIds(item, direction))
  if (typeof value !== 'object') {
    if (typeof value === 'number' && isIdKey(key)) {
      if (!Number.isSafeInteger(value)) throw new ApiError('服务端返回了不安全的数字 ID')
      return String(value)
    }
    if (
      direction === 'response' &&
      typeof value === 'string' &&
      key &&
      NUMERIC_RESPONSE_FIELDS.has(key)
    ) {
      const numeric = Number(value)
      return Number.isFinite(numeric) ? numeric : value
    }
    return value
  }

  if (Object.getPrototypeOf(value) !== Object.prototype) return value

  return Object.fromEntries(
    Object.entries(value).map(([entryKey, entryValue]) => [
      entryKey,
      normalizeProtocolIds(entryValue, direction, entryKey),
    ]),
  )
}

function isIdKey(key?: string): boolean {
  return Boolean(key && (key === 'id' || key.endsWith('Id')))
}

httpClient.interceptors.request.use((config) => {
  const token = readStoredToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  if (config.data && typeof config.data === 'object') {
    config.data = normalizeProtocolIds(config.data, 'request')
  }
  return config
})

httpClient.interceptors.response.use((response) => {
  response.data = normalizeProtocolIds(response.data, 'response')
  return response
})

export async function apiRequest<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const response = await httpClient.request<ApiEnvelope<T>>(config)
    const envelope = response.data

    if (envelope?.code !== 0) {
      if (envelope?.code === 200000) clearStoredSession()
      throw new ApiError(envelope?.message || '操作失败', {
        code: envelope?.code,
        status: response.status,
        traceId: envelope?.traceId,
      })
    }

    return envelope.data
  } catch (error) {
    if (error instanceof ApiError) throw error

    if (axios.isAxiosError<ApiEnvelope<unknown>>(error)) {
      const status = error.response?.status
      const envelope = error.response?.data
      if (status === 401) clearStoredSession()

      let message = envelope?.message
      if (!message) {
        if (error.code === 'ECONNABORTED') message = '请求超时，请稍后重试'
        else if (!error.response) message = '无法连接服务器，请检查后端是否已启动'
        else if (status === 403) {
          /*
           * CORS 被拒时 Spring 会返回 403 + "Invalid CORS request"。这种情况报「没有操作权限」
           * 会把人带偏 —— 看起来像权限没配，实际是来源不在白名单里（见 loom.cors.allowed-origins）。
           * 这里按响应体把两种 403 分开说。
           */
          const body = typeof envelope === 'string' ? envelope : ''
          message = body.includes('Invalid CORS request')
            ? '请求被跨域策略拒绝：当前页面来源不在后端白名单里（loom.cors.allowed-origins）'
            : '没有操作权限'
        } else if (status && status >= 500) message = '服务器内部错误，请稍后重试'
        else message = '请求失败，请稍后重试'
      }

      throw new ApiError(message, {
        code: envelope?.code,
        status,
        traceId: envelope?.traceId,
      })
    }

    throw new ApiError(error instanceof Error ? error.message : '未知错误')
  }
}
