export interface UserProfile {
  id: number
  account: string
  email: string
  status: number
  roles: string[]
  permissions: string[]
  lastLoginTime: string | null
  createTime: string
}

export interface LoginResponse {
  token: string
  tokenType: string
  expiresIn: number
  user: UserProfile
}

export interface AuthOptions {
  registerEnabled: boolean
  loginEnabled: boolean
  emailCodeEnabled: boolean
  passwordResetEnabled: boolean
}

export type EmailCodeScene = 'REGISTER' | 'RESET_PASSWORD'
