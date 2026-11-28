import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { shipmentsApi } from '../../api/shipments'
import type { BulkShipmentResponse, CourierCode, SendShipmentRequest, Shipment, ShipmentListParams } from '../../types/shipment'
import { customerKeys } from '../customers/queries'
import { inventoryKeys } from '../inventory/queries'
import { errorCodeOf } from '../orders/orderErrors'
import { orderKeys } from '../orders/queries'
import { BULK_BATCH_SIZE } from './shipmentLabels'
import { shipmentErrorMessage } from './shipmentErrors'

/** Cache keys. Everything under ['shipments'] belongs to this feature. */
export const shipmentKeys = {
  all: ['shipments'] as const,
  lists: () => [...shipmentKeys.all, 'list'] as const,
  list: (params: ShipmentListParams) => [...shipmentKeys.lists(), params] as const,
  detail: (id: string) => [...shipmentKeys.all, 'detail', id] as const,
  forOrder: (orderId: string) => [...shipmentKeys.all, 'order', orderId] as const,
}

/** While BOOKING the server retries the courier by itself; poll until it settles. */
const BOOKING_POLL_MS = 5_000

/**
 * A shipment change can move its order (pickup ships it, delivery delivers it, with stock and
 * customer counters): mark all of that stale.
 */
function invalidateAround(queryClient: QueryClient, orderId: string) {
  void queryClient.invalidateQueries({ queryKey: shipmentKeys.lists() })
  void queryClient.invalidateQueries({ queryKey: shipmentKeys.forOrder(orderId) })
  void queryClient.invalidateQueries({ queryKey: orderKeys.detail(orderId) })
  void queryClient.invalidateQueries({ queryKey: orderKeys.timeline(orderId) })
  void queryClient.invalidateQueries({ queryKey: orderKeys.lists() })
  void queryClient.invalidateQueries({ queryKey: inventoryKeys.all })
  void queryClient.invalidateQueries({ queryKey: customerKeys.all })
}

export function useShipmentList(params: ShipmentListParams) {
  return useQuery({ queryKey: shipmentKeys.list(params), queryFn: () => shipmentsApi.list(params), placeholderData: keepPreviousData })
}

/** Every booking attempt of one order, newest first. */
export function useOrderShipments(orderId: string) {
  return useQuery({ queryKey: shipmentKeys.forOrder(orderId), queryFn: () => shipmentsApi.forOrder(orderId) })
}

/** One shipment with its events. Polls while BOOKING; a status change refreshes the order too. */
export function useShipment(id: string | undefined) {
  const queryClient = useQueryClient()
  return useQuery({
    queryKey: shipmentKeys.detail(id ?? ''),
    queryFn: async () => {
      const before = queryClient.getQueryData<Shipment>(shipmentKeys.detail(id ?? ''))
      const shipment = await shipmentsApi.get(id ?? '')
      if (before && before.status !== shipment.status) invalidateAround(queryClient, shipment.orderId)
      return shipment
    },
    enabled: !!id,
    refetchInterval: (query) => (query.state.data?.status === 'BOOKING' ? BOOKING_POLL_MS : false),
  })
}

/** Every shipment write returns the whole shipment: cache it and refresh what it may have moved. */
export function useStoreShipment() {
  const queryClient = useQueryClient()
  return useCallback(
    (shipment: Shipment) => {
      queryClient.setQueryData(shipmentKeys.detail(shipment.id), shipment)
      invalidateAround(queryClient, shipment.orderId)
    },
    [queryClient],
  )
}

export function useSendShipment(orderId: string) {
  const store = useStoreShipment()
  return useMutation({ mutationFn: (body: SendShipmentRequest) => shipmentsApi.send(orderId, body), onSuccess: store })
}

export function useTrackShipment(id: string) {
  const store = useStoreShipment()
  return useMutation({ mutationFn: () => shipmentsApi.track(id), onSuccess: store })
}

export function useCancelShipment(id: string) {
  const store = useStoreShipment()
  return useMutation({ mutationFn: (reason: string) => shipmentsApi.cancel(id, reason), onSuccess: store })
}

export interface BulkSendInput {
  provider: CourierCode
  orderIds: string[]
  /** Called after each batch with how many orders are done. */
  onProgress?: (done: number, total: number) => void
}

/**
 * Bulk send in batches of 20 (the backend limit: each order may wait up to 10 s for the courier),
 * one batch after the other. A batch that fails as a whole (network, 5xx) marks its orders failed
 * and the next batch still runs, so bookings of earlier batches are never lost from the result.
 */
export function useBulkSendShipments() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ provider, orderIds, onProgress }: BulkSendInput): Promise<BulkShipmentResponse> => {
      const merged: BulkShipmentResponse = { provider, succeeded: 0, failed: 0, results: [] }
      for (let start = 0; start < orderIds.length; start += BULK_BATCH_SIZE) {
        const batch = orderIds.slice(start, start + BULK_BATCH_SIZE)
        try {
          const response = await shipmentsApi.bulk({ provider, orderIds: batch })
          merged.succeeded += response.succeeded
          merged.failed += response.failed
          merged.results.push(...response.results)
        } catch (e) {
          // An unknown courier fails every batch the same way: report it once, as an error.
          if (start === 0 && errorCodeOf(e) === 'COURIER_NOT_AVAILABLE') throw e
          const message = shipmentErrorMessage(e, 'Could not send this batch.')
          merged.failed += batch.length
          merged.results.push(...batch.map((orderId) => ({ orderId, ok: false, errorCode: errorCodeOf(e) ?? 'REQUEST_FAILED', message })))
        }
        onProgress?.(Math.min(start + BULK_BATCH_SIZE, orderIds.length), orderIds.length)
      }
      return merged
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: shipmentKeys.all })
      void queryClient.invalidateQueries({ queryKey: orderKeys.all })
    },
  })
}
