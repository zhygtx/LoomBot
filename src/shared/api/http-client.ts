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

httpClient.interceptors.request.use((config) => {
  const token = readStoredToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
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
        else if (status === 403) message = '没有操作权限'
        else if (status && status >= 500) message = '服务器内部错误，请稍后重试'
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
