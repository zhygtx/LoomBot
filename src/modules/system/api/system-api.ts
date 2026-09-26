import { apiRequest } from '@shared/api/http-client'

import type {
  BackendPermission,
  MenuItem,
  MenuPayload,
  MenuSortGroup,
  RoleMenuRelation,
  RolePermissionRelation,
  RoleRelation,
  RoleSummary,
  SystemConfig,
  SystemConfigUpdate,
  UserRoleRelation,
} from '../model/types'

export const systemApi = {
  listBackendPermissions: () =>
    apiRequest<BackendPermission[]>({
      url: '/system/permissions/backend-required',
      method: 'GET',
    }),

  listPermissions: () =>
    apiRequest<BackendPermission[]>({
      url: '/system/permissions',
      method: 'GET',
    }),

  updatePermissionStatus: (id: string, enabled: boolean) =>
    apiRequest<null>({
      url: '/system/permissions/' + id + '/status',
      method: 'PUT',
      data: { enabled },
    }),

  listMenus: () =>
    apiRequest<MenuItem[]>({
      url: '/system/menus',
      method: 'GET',
    }),

  listRelationMenus: () =>
    apiRequest<MenuItem[]>({
      url: '/system/menus/available',
      method: 'GET',
    }),

  navigation: () =>
    apiRequest<MenuItem[]>({
      url: '/system/menus/navigation',
      method: 'GET',
    }),

  createMenu: (payload: MenuPayload) =>
    apiRequest<MenuItem>({
      url: '/system/menus',
      method: 'POST',
      data: payload,
    }),

  updateMenu: (id: string, payload: MenuPayload) =>
    apiRequest<MenuItem>({
      url: '/system/menus/' + id,
      method: 'PUT',
      data: payload,
    }),

  deleteMenu: (id: string) =>
    apiRequest<null>({
      url: '/system/menus/' + id,
      method: 'DELETE',
    }),

  updateMenuStatus: (id: string, enabled: boolean) =>
    apiRequest<null>({
      url: '/system/menus/' + id + '/status',
      method: 'PUT',
      data: { enabled },
    }),

  /**
   * 批量排序。只提交受影响的层级：拖拽一次会同时改「旧父级」和「新父级」两层，
   * 两组一起提交才能落在一个事务里，不会出现摘下来还没挂上去的中间态。
   */
  sortMenus: (groups: MenuSortGroup[]) =>
    apiRequest<null>({
      url: '/system/menus/sort',
      method: 'PUT',
      data: { groups },
    }),

  listRoleRelations: () =>
    apiRequest<RoleRelation[]>({ url: '/system/relations/roles', method: 'GET' }),

  listRoleSummaries: () =>
    apiRequest<RoleSummary[]>({ url: '/system/relations/roles/summaries', method: 'GET' }),

  listRolePermissions: () =>
    apiRequest<RolePermissionRelation[]>({
      url: '/system/relations/roles/permissions',
      method: 'GET',
    }),

  listRoleMenus: () =>
    apiRequest<RoleMenuRelation[]>({ url: '/system/relations/roles/menus', method: 'GET' }),

  listUserRoles: () =>
    apiRequest<UserRoleRelation[]>({ url: '/system/relations/users/roles', method: 'GET' }),

  updateUserRoles: (id: string, ids: string[]) =>
    apiRequest<null>({
      url: '/system/relations/users/' + id + '/roles',
      method: 'PUT',
      data: { ids },
    }),

  updateUserRolesBatch: (updates: Array<{ userId: string; enabled: boolean; roleIds: string[] }>) =>
    apiRequest<null>({
      url: '/system/relations/users/roles/batch',
      method: 'PUT',
      data: { updates },
    }),

  updateRolePermissions: (id: string, permissions: string[]) =>
    apiRequest<null>({
      url: '/system/relations/roles/' + id + '/permissions',
      method: 'PUT',
      data: { permissions },
    }),

  updateRoleMenus: (id: string, ids: string[]) =>
    apiRequest<null>({
      url: '/system/relations/roles/' + id + '/menus',
      method: 'PUT',
      data: { ids },
    }),

  listConfigs: () =>
    apiRequest<SystemConfig[]>({
      url: '/system/config',
      method: 'GET',
    }),

  /**
   * 整页一次提交。返回落库后的完整条目，前端据此重置「已修改」基线。
   *
   * 每条只有 key + value（JSON 文本）—— 没有单独的启用状态，开关在值里面。
   */
  updateConfigs: (updates: SystemConfigUpdate[]) =>
    apiRequest<SystemConfig[]>({
      url: '/system/config',
      method: 'PUT',
      data: { updates },
    }),
}
