import { apiRequest } from '@shared/api/http-client'

import type {
  ConnectionCreatePayload,
  ConnectionPage,
  ConnectionRecord,
  ConnectionTypeDescriptor,
} from '../model/types'

export const connectionsApi = {
  listTypes: () =>
    apiRequest<ConnectionTypeDescriptor[]>({
      url: '/connection/types',
      method: 'GET',
    }),

  list: () =>
    apiRequest<ConnectionPage>({
      url: '/connection',
      method: 'GET',
      params: { pageNum: 1, pageSize: 200 },
    }),

  create: (payload: ConnectionCreatePayload) =>
    apiRequest<ConnectionRecord>({
      url: '/connection',
      method: 'POST',
      data: payload,
    }),

  enable: (id: string) =>
    apiRequest<ConnectionRecord>({
      url: `/connection/${id}/enable`,
      method: 'POST',
    }),

  disable: (id: string) =>
    apiRequest<ConnectionRecord>({
      url: `/connection/${id}/disable`,
      method: 'POST',
    }),

  remove: (id: string) =>
    apiRequest<null>({
      url: `/connection/${id}`,
      method: 'DELETE',
    }),
}
