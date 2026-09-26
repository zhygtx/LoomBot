export interface BackendPermission {
  id: string
  name: string
  permission: string
  enabled: boolean
  backendRequired: boolean
  lastSeenTime: string | null
}

export type MenuType = 'CATALOG' | 'MENU'

export interface MenuItem {
  id: string
  parentId: string
  type: MenuType
  name: string
  routeName: string | null
  path: string | null
  componentKey: string | null
  iconKey: string | null
  redirect: string | null
  sort: number
  visible: boolean
  keepAlive: boolean
  enabled: boolean
  remark: string | null
}

export interface MenuPayload {
  parentId: string
  type: MenuType
  name: string
  routeName: string
  path: string
  componentKey: string
  iconKey: string
  redirect: string
  sort: number
  visible: boolean
  keepAlive: boolean
  remark: string
}

/**
 * 一个层级排序后的完整兄弟顺序。拖拽一次可能同时影响两个层级（旧父级、新父级），
 * 所以排序接口收的是这个结构的数组。
 */
export interface MenuSortGroup {
  parentId: string
  ids: string[]
}

export interface RoleRelation {
  id: string
  code: string
  name: string
  enabled: boolean
  permissionIds: string[]
  menuIds: string[]
}

export interface RoleSummary {
  id: string
  code: string
  name: string
  enabled: boolean
}

export interface RolePermissionRelation extends RoleSummary {
  permissionIds: string[]
}

export interface RoleMenuRelation extends RoleSummary {
  menuIds: string[]
}

export interface UserRoleRelation {
  id: string
  email: string
  enabled: boolean
  roleIds: string[]
}

/**
 * 配置值的形状，由**后端从 JSON 值本身算出来**（不是数据库里的一列）。
 *
 * <p>前端据此挑编辑器。注意 `INVALID`：值不是合法 JSON 时后端不会让整个请求失败，而是把它
 * 标成这个形状 —— 配置页要能把它显示成一条待修复的脏数据，并允许就地改回合法 JSON。
 */
export type SystemConfigValueKind =
  'BOOLEAN' | 'NUMBER' | 'STRING' | 'OBJECT' | 'ARRAY' | 'NULL' | 'INVALID'

export interface SystemConfig {
  id: string
  configKey: string
  /** JSON 文本。一条配置就这一个值 —— 开关也是它的一部分，不是独立字段。 */
  configValue: string
  valueKind: SystemConfigValueKind | string
  configGroup: string
  name: string
  description: string | null
  builtin: boolean
  /**
   * 值里那个「开关」的键名（目前是 `enabled`），纯参数配置为 null。
   *
   * 顺带一提：**不能**用 `'enabled' in obj` 去判断这个键在不在 —— 原型链上的键会让它误报，
   * 所以键名只能由后端明确给出。
   */
  switchKey: string | null
  /** 这条配置不允许被关掉。 */
  locked: boolean
  /** 不允许关掉的原因，原样来自后端；`locked` 为 false 时是 null。 */
  lockedReason: string | null
}

/** 批量保存里的一条：键 + JSON 值。没有单独的启用状态 —— 开关在值里面。 */
export interface SystemConfigUpdate {
  key: string
  value: string
}
