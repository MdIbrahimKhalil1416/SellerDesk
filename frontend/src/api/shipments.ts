import type { Page } from '../types/api'
import type {
  BulkShipmentRequest,
  BulkShipmentResponse,
  SendShipmentRequest,
  Shipment,
  ShipmentListParams,
  ShipmentSummary,
} from '../types/shipment'
import { api } from './client'

const BASE = '/api/v1/shipments'
const path = (id: string) => `${BASE}/${encodeURIComponent(id)}`
const orderPath = (orderId: string) => `/api/v1/orders/${encodeURIComponent(orderId)}`

/**
 * Thin wrappers around the courier endpoints (docs/modules/COURIER.md §4). No shopId anywhere:
 * the backend takes it from the token.
 */
export const shipmentsApi = {
  async list(params: ShipmentListParams): Promise<Page<ShipmentSummary>> {
    const { data } = await api.get<Page<ShipmentSummary>>(BASE, {
      params: Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== '')),
    })
    return data
  },

  /** Every booking attempt of one order, newest first. */
  async forOrder(orderId: string): Promise<ShipmentSummary[]> {
    const { data } = await api.get<ShipmentSummary[]>(`${orderPath(orderId)}/shipments`)
    return data
  },

  async get(id: string): Promise<Shipment> {
    const { data } = await api.get<Shipment>(path(id))
    return data
  },

  /**
   * 201 booked (CREATED), 202 the courier did not answer (BOOKING, retried automatically),
   * 200 already booked with this courier. All three return the shipment.
   */
  async send(orderId: string, body: SendShipmentRequest): Promise<Shipment> {
    const { data } = await api.post<Shipment>(`${orderPath(orderId)}/shipment`, body)
    return data
  },

  /** Asks the courier for the current status (at most once a minute); may move the order. */
  async track(id: string): Promise<Shipment> {
    const { data } = await api.post<Shipment>(`${path(id)}/track`)
    return data
  },

  /** Only before pickup (BOOKING, CREATED). The order stays PACKED and can be sent again. */
  async cancel(id: string, reason: string): Promise<Shipment> {
    const { data } = await api.post<Shipment>(`${path(id)}/cancel`, { reason })
    return data
  },

  /** At most 20 orders: use `sendInBatches` in the shipments queries for more. */
  async bulk(body: BulkShipmentRequest): Promise<BulkShipmentResponse> {
    const { data } = await api.post<BulkShipmentResponse>(`${BASE}/bulk`, body)
    return data
  },
}
