import { apiRequest } from '@shared/api/http-client'

import type {
  BackendPermission,
  MenuItem,
  MenuPayload,
  RoleMenuRelation,
  RolePermissionRelation,
  RoleRelation,
  RoleSummary,
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

  updateRolePermissions: (id: string, ids: string[]) =>
    apiRequest<null>({
      url: '/system/relations/roles/' + id + '/permissions',
      method: 'PUT',
      data: { ids },
    }),

  updateRoleMenus: (id: string, ids: string[]) =>
    apiRequest<null>({
      url: '/system/relations/roles/' + id + '/menus',
      method: 'PUT',
      data: { ids },
    }),
}
