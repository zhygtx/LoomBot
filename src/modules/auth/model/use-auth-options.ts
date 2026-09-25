import { useQuery } from '@tanstack/vue-query'

import { authApi } from '../api/auth-api'

export function useAuthOptions() {
  return useQuery({
    queryKey: ['system', 'public', 'auth-options'],
    queryFn: authApi.options,
    staleTime: 30_000,
    retry: 1,
  })
}
