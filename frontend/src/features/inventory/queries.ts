import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { inventoryApi } from '../../api/inventory'
import type { AdjustmentRequest, InventoryListParams, MovementListParams, RestockRequest } from '../../types/inventory'
import { productKeys } from '../products/queries'

/** Cache keys. Everything under ['inventory'] belongs to this feature. */
export const inventoryKeys = {
  all: ['inventory'] as const,
  list: (params: InventoryListParams) => [...inventoryKeys.all, 'list', params] as const,
  variant: (variantId: string) => [...inventoryKeys.all, 'variant', variantId] as const,
  movements: (params: MovementListParams) => [...inventoryKeys.all, 'movements', params] as const,
}

/** keepPreviousData: the old page stays visible while the next one loads (no flicker). */
export function useInventoryList(params: InventoryListParams) {
  return useQuery({ queryKey: inventoryKeys.list(params), queryFn: () => inventoryApi.list(params), placeholderData: keepPreviousData })
}

export function useVariantStock(variantId: string | undefined) {
  return useQuery({
    queryKey: inventoryKeys.variant(variantId ?? ''),
    queryFn: () => inventoryApi.variant(variantId ?? ''),
    enabled: !!variantId,
  })
}

export function useMovements(params: MovementListParams) {
  return useQuery({ queryKey: inventoryKeys.movements(params), queryFn: () => inventoryApi.movements(params), placeholderData: keepPreviousData })
}

/**
 * After a stock change every stock number on screen may be stale: all inventory queries, and
 * product details (they show stock to users with STOCK_VIEW).
 */
function useInvalidateStock() {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: inventoryKeys.all })
    void queryClient.invalidateQueries({ queryKey: productKeys.all })
  }
}

export function useRestock(variantId: string) {
  const invalidate = useInvalidateStock()
  return useMutation({ mutationFn: (body: RestockRequest) => inventoryApi.restock(variantId, body), onSuccess: invalidate })
}

export function useAdjust(variantId: string) {
  const invalidate = useInvalidateStock()
  return useMutation({ mutationFn: (body: AdjustmentRequest) => inventoryApi.adjust(variantId, body), onSuccess: invalidate })
}
