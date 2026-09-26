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
