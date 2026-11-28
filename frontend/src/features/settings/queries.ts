import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { shopApi } from '../../api/shop'
import type { Shop, UpdateShopRequest } from '../../types/shop'
import { useAuth } from '../auth/authContext'

/** One cache entry: the logged-in user's shop. Cleared on logout with the rest of the cache. */
export const shopKeys = {
  current: ['shop'] as const,
}

/**
 * GET /shop. Used by the settings page AND the top bar / sidebar (name + logo). Because both read
 * the same cache entry, saving on the settings page updates the top bar at once, without a reload.
 *
 * Disabled for PLATFORM_ADMIN (no shop).
 */
export function useShop() {
  const { shop } = useAuth()
  return useQuery({ queryKey: shopKeys.current, queryFn: () => shopApi.get(), enabled: !!shop, staleTime: 5 * 60_000 })
}

/** Every shop write returns the whole shop: put it straight into the cache. */
export function useStoreShop() {
  const queryClient = useQueryClient()
  return useCallback((shop: Shop) => queryClient.setQueryData(shopKeys.current, shop), [queryClient])
}

export function useUpdateShop() {
  const store = useStoreShop()
  return useMutation({ mutationFn: (body: UpdateShopRequest) => shopApi.update(body), onSuccess: store })
}

export function useDeleteLogo() {
  const store = useStoreShop()
  return useMutation({ mutationFn: () => shopApi.deleteLogo(), onSuccess: store })
}
