import { readonly, ref } from 'vue'

/**
 * 全局消息提示（toast）。
 *
 * 为什么是模块级 store，而不是 provide / inject：
 * 调用方遍布各处 —— 页面、布局，将来还可能是 HTTP 层。`provide` 要求注入方必须待在某棵组件树里，
 * 而这些调用点随时可能跑到树外（例如 axios 拦截器）。全局只有一块提示区，模块级单例正好对上。
 *
 * 为什么不用 reka-ui 的 Toast：
 * `reka-ui` 在依赖里，但全工程至今没用到它 —— 本项目的按钮、输入框、卡片全是手写的。
 * 为一组提示条引入第一处 reka-ui 用法，会让「结构归它、皮肤归我们」这件事分裂成两个体系，
 * 而皮肤又要对齐 `--sys-*` 令牌。它带来的收益（列表管理、滑动关闭）远小于这份割裂，
 * 所以沿用 BaseNotice 的做法：自己写，配色走同一套语义令牌。
 *
 * 定位：**只放瞬时反馈** —— 操作成功、操作失败、加载失败、提交被拒。
 * 「注册已关闭」这类**持久状态**不在这里：提示条几秒后消失，而按钮一直是灰的，
 * 用户会盯着一个点不动的按钮找不到原因。这类状态留在页面里，见决策记录 D72。
 */

export type MessageType = 'success' | 'error' | 'warning' | 'info'

export interface MessageItem {
  id: number
  type: MessageType
  text: string
}

/** 默认停留时长。传 `0` 表示不自动消失，必须手动关掉。 */
export const MESSAGE_DURATION = 3000

/**
 * 同屏最多几条。
 *
 * 超过就把最旧的一条挤掉：一次操作可能连发三条，堆满整屏会把界面盖住，
 * 而提示的价值随条数递减 —— 第四条开始就只是噪音了。
 */
const MAX_VISIBLE = 4

const items = ref<MessageItem[]>([])
const timers = new Map<number, ReturnType<typeof setTimeout>>()
let sequence = 0

/** 只读列表，交给 `MessageHost` 渲染。 */
export const messageItems = readonly(items)

/** 关掉一条（用户点关闭、倒计时结束、或被新消息挤掉）。 */
export function dismissMessage(id: number): void {
  const timer = timers.get(id)
  if (timer !== undefined) {
    clearTimeout(timer)
    timers.delete(id)
  }
  items.value = items.value.filter((item) => item.id !== id)
}

/** 关掉全部（登出时用，避免上一个会话的提示留在新页面上）。 */
export function dismissAllMessages(): void {
  for (const id of [...timers.keys()]) dismissMessage(id)
  items.value = []
}

function scheduleDismiss(id: number, duration: number): void {
  const timer = timers.get(id)
  if (timer !== undefined) clearTimeout(timer)
  if (duration <= 0) {
    timers.delete(id)
    return
  }
  timers.set(
    id,
    setTimeout(() => dismissMessage(id), duration),
  )
}

function push(type: MessageType, text: string, duration = MESSAGE_DURATION): void {
  const content = text.trim()
  if (!content) return

  // 同一条消息已经在屏幕上时不再叠一条，只把它的倒计时重置：
  // 连点两次保存、或者并发的几个请求同时失败，叠出四条一模一样的提示比只留一条更糟。
  const existing = items.value.find((item) => item.type === type && item.text === content)
  if (existing) {
    scheduleDismiss(existing.id, duration)
    return
  }

  const id = (sequence += 1)
  items.value = [...items.value, { id, type, text: content }]

  while (items.value.length > MAX_VISIBLE) {
    const oldest = items.value[0]
    if (oldest === undefined) break
    dismissMessage(oldest.id)
  }

  scheduleDismiss(id, duration)
}

/**
 * 页面里唯一的提示入口。
 *
 * ```ts
 * message.success('菜单已创建')
 * message.error(error instanceof ApiError ? error.message : '菜单保存失败，请稍后重试')
 * ```
 *
 * 空文本会被忽略，所以 `error.message` 拿到空串时不会弹出一个空壳提示。
 */
export const message = {
  success(text: string, duration?: number): void {
    push('success', text, duration)
  },
  error(text: string, duration?: number): void {
    push('error', text, duration)
  },
  warning(text: string, duration?: number): void {
    push('warning', text, duration)
  },
  info(text: string, duration?: number): void {
    push('info', text, duration)
  },
}
