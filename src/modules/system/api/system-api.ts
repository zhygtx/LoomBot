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

  /**
   * 删除一条权限定义（**真删**）。后端会连带清掉所有角色对它的授权。
   *
   * 原来这里还有 `updatePermissionStatus`（启停单条权限）。它随 `sys_permission.status`
   * 一起删掉了：权限是否生效由角色授权里的勾选决定，不是挂在权限定义上的开关。
   */
  deletePermission: (id: string) =>
    apiRequest<null>({
      url: '/system/relations/permissions/' + id,
      method: 'DELETE',
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

  /**
   * 删除菜单（**真删**，不是逻辑删除）。
   *
   * 后端会拒绝还有子菜单的节点，并在同一个事务里清掉角色菜单关联。
   */
  deleteMenu: (id: string) =>
    apiRequest<null>({
      url: '/system/menus/' + id,
      method: 'DELETE',
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

  /**
   * 删除一个角色（**真删**）。后端会连带清掉它的权限授权、菜单授权和用户绑定。
   *
   * 内置角色、以及仍有启用用户挂着的角色会被后端拒绝 —— 与原来「停用角色」上的那条守卫
   * 是同一件事，只是角色状态列删掉之后它平移到了删除动作上。
   */
  deleteRole: (id: string) =>
    apiRequest<null>({
      url: '/system/relations/roles/' + id,
      method: 'DELETE',
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
