const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const ACCOUNT_PATTERN = /^[a-zA-Z][a-zA-Z0-9_]{2,19}$/
const CODE_PATTERN = /^\d{6}$/

export function validateEmail(value: string): string {
  if (!value.trim()) return '请输入邮箱'
  if (value.length > 128 || !EMAIL_PATTERN.test(value.trim())) return '请输入有效的邮箱地址'
  return ''
}

export function validateAccount(value: string): string {
  if (!value.trim()) return '请输入账号'
  if (!ACCOUNT_PATTERN.test(value.trim())) {
    return '账号需 3~20 位，以字母开头，只含字母、数字、下划线'
  }
  return ''
}

export function validatePassword(value: string, label = '密码'): string {
  if (!value) return `请输入${label}`
  if (value.length < 6 || value.length > 20) return `${label}长度需为 6~20 位`
  return ''
}

export function validateLoginPassword(value: string): string {
  return value ? '' : '请输入密码'
}

export function validateCode(value: string): string {
  if (!value) return '请输入验证码'
  return CODE_PATTERN.test(value) ? '' : '验证码为 6 位数字'
}
