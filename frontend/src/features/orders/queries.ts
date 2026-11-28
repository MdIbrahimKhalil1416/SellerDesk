import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { inventoryApi } from '../../api/inventory'
import { ordersApi } from '../../api/orders'
import { productsApi } from '../../api/products'
import type { BulkStatusRequest, CreateOrderRequest, Order, OrderListParams, ReturnRequest, ShipRequest } from '../../types/order'
import { customerKeys } from '../customers/queries'
import { inventoryKeys } from '../inventory/queries'

/** Cache keys. Everything under ['orders'] belongs to this feature. */
export const orderKeys = {
  all: ['orders'] as const,
  lists: () => [...orderKeys.all, 'list'] as const,
  list: (params: OrderListParams) => [...orderKeys.lists(), params] as const,
  detail: (id: string) => [...orderKeys.all, 'detail', id] as const,
  timeline: (id: string) => [...orderKeys.all, 'timeline', id] as const,
  productLookup: ['orders', 'product-lookup'] as const,
  stock: (q: string) => ['orders', 'stock', q] as const,
}

export function useOrderList(params: OrderListParams) {
  return useQuery({ queryKey: orderKeys.list(params), queryFn: () => ordersApi.list(params), placeholderData: keepPreviousData })
}

export function useOrder(id: string | undefined) {
  return useQuery({ queryKey: orderKeys.detail(id ?? ''), queryFn: () => ordersApi.get(id ?? ''), enabled: !!id })
}

export function useOrderTimeline(id: string) {
  return useQuery({ queryKey: orderKeys.timeline(id), queryFn: () => ordersApi.timeline(id) })
}

/** Active products and variants for the picker. The backend caches it too; 1 minute here. */
export function useProductLookup() {
  return useQuery({ queryKey: orderKeys.productLookup, queryFn: () => productsApi.lookup(), staleTime: 60_000 })
}

/**
 * Live `available` per variant for the picker (STOCK_VIEW only): the stock list searched with the
 * same text (exact SKU or name prefix). Never cached for long: stock moves all the time.
 */
export function usePickerStock(q: string, enabled: boolean) {
  return useQuery({
    queryKey: orderKeys.stock(q),
    queryFn: () => inventoryApi.list({ q: q || undefined, stockStatus: 'ALL', includeInactive: false, sort: 'name,asc', page: 0, size: 100 }),
    enabled,
    staleTime: 10_000,
    placeholderData: keepPreviousData,
  })
}

/**
 * Every order write returns the whole order: put it in the cache and mark what it may have
 * changed as stale: order lists, its timeline, stock numbers, customer counters.
 */
export function useStoreOrder() {
  const queryClient = useQueryClient()
  return useCallback(
    (order: Order) => {
      queryClient.setQueryData(orderKeys.detail(order.id), order)
      void queryClient.invalidateQueries({ queryKey: orderKeys.lists() })
      void queryClient.invalidateQueries({ queryKey: orderKeys.timeline(order.id) })
      void queryClient.invalidateQueries({ queryKey: ['orders', 'stock'] })
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.all })
      void queryClient.invalidateQueries({ queryKey: customerKeys.all })
    },
    [queryClient],
  )
}

export function useCreateOrder() {
  const store = useStoreOrder()
  return useMutation({ mutationFn: (body: CreateOrderRequest) => ordersApi.create(body), onSuccess: store })
}

/** One status change. The variables say which action and carry its body. */
export type OrderActionInput =
  | { action: 'CONFIRM' | 'PACK' | 'DELIVER'; note?: string }
  | { action: 'SHIP'; body: ShipRequest }
  | { action: 'RETURN'; body: ReturnRequest }
  | { action: 'CANCEL'; reason: string }

export function useOrderAction(id: string) {
  const store = useStoreOrder()
  return useMutation({
    mutationFn: (input: OrderActionInput) => {
      switch (input.action) {
        case 'CONFIRM':
          return ordersApi.confirm(id, input.note)
        case 'PACK':
          return ordersApi.pack(id, input.note)
        case 'DELIVER':
          return ordersApi.deliver(id, input.note)
        case 'SHIP':
          return ordersApi.ship(id, input.body)
        case 'RETURN':
          return ordersApi.returnOrder(id, input.body)
        case 'CANCEL':
          return ordersApi.cancel(id, input.reason)
      }
    },
    onSuccess: store,
  })
}

/** Many orders at once. Some may fail; everything order-related is refreshed afterwards. */
export function useBulkStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: BulkStatusRequest) => ordersApi.bulkStatus(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: orderKeys.all })
      void queryClient.invalidateQueries({ queryKey: inventoryKeys.all })
      void queryClient.invalidateQueries({ queryKey: customerKeys.all })
    },
  })
}
