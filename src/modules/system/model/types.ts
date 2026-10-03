/**
 * 权限目录里的一条。
 *
 * 没有 `enabled` —— `sys_permission.status` 已删除。权限是否生效完全由**角色授权**决定：
 * 没被任何角色勾选就等于没人拥有。前端也不该再照着某个「权限被停用」的状态写判断。
 */
export interface BackendPermission {
  id: string
  name: string
  permission: string
  backendRequired: boolean
  lastSeenTime: string | null
}

export type MenuType = 'CATALOG' | 'MENU'

/**
 * 菜单树里的一条。
 *
 * 没有 `enabled` —— `sys_menu.status` 已删除。侧边栏可见性只由 `visible` 一个字段决定：
 * 它是显式声明的「隐藏但保留」，和「删除」不是一回事。
 */
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

/**
 * 角色列表里的一行。
 *
 * 没有 `enabled` —— `sys_role.status` 已删除。角色是一组权限的集合，不想让人用就把它从用户
 * 身上摘掉；留一个「停用但还绑着用户」的中间态只会让「这个角色的权限到底算不算数」变成
 * 一个每次都要重新确认的问题。
 */
export interface RoleSummary {
  id: string
  code: string
  name: string
}

export interface RolePermissionRelation extends RoleSummary {
  permissionIds: string[]
}

export interface RoleMenuRelation extends RoleSummary {
  menuIds: string[]
}

/**
 * 用户角色表里的一行。
 *
 * `enabled` 在这里**保留**，因为它来自 `sys_user.status` —— 全库唯一留下的状态列。
 * 它是账号开关（封号 / 解封），不是记录有效性。
 */
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

/**
 * 一个插件库在后台的样子。
 *
 * `valid=false` 表示文件夹在、但 `repo.json` 读不出来（`key` 为 null，原因在 `error`）。
 * 这种库会被同步器直接跳过，所以必须显示出来，藏起来只会让人以为配好了。
 * 删除库是连文件夹带状态行一起删的，所以列表里不会出现"没有文件夹的库"。
 */
export interface PluginRepo {
  key: string | null
  folder: string | null
  url: string | null
  branch: string | null
  scanner: string | null
  valid: boolean
  error: string | null
  localPath: string | null
  lastCommitHash: string | null
  lastPullTime: string | null
  lastScanTime: string | null
  lastError: string | null
}

/**
 * 声明一个插件库。身份（key）在路径上，不在这里 —— 两处都能改的话，
 * 「改 key」到底算新建还是改名就说不清了。
 */
export interface PluginRepoPayload {
  url: string
  branch: string
  scanner: string
}
