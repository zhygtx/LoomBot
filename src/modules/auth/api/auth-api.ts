import { apiRequest } from '@shared/api/http-client'

import type { AuthOptions, EmailCodeScene, LoginResponse, UserProfile } from '../model/types'

export const authApi = {
  options: () =>
    apiRequest<AuthOptions>({
      url: '/system/public/auth-options',
      method: 'GET',
    }),

  login: (payload: { email: string; password: string }) =>
    apiRequest<LoginResponse>({
      url: '/auth/login',
      method: 'POST',
      data: payload,
    }),

  register: (payload: { email: string; code: string; password: string }) =>
    apiRequest<number>({
      url: '/auth/register',
      method: 'POST',
      data: payload,
    }),

  sendEmailCode: (payload: { email: string; scene: EmailCodeScene }) =>
    apiRequest<null>({
      url: '/auth/email-code',
      method: 'POST',
      data: payload,
    }),

  resetPassword: (payload: { email: string; code: string; newPassword: string }) =>
    apiRequest<null>({
      url: '/auth/password/reset',
      method: 'POST',
      data: payload,
    }),

  profile: () =>
    apiRequest<UserProfile>({
      url: '/auth/me',
      method: 'GET',
    }),
}
