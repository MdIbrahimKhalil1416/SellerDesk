import type { Page } from '../types/api'
import type {
  BulkStatusRequest,
  BulkStatusResponse,
  CreateOrderRequest,
  Order,
  OrderListParams,
  OrderSummary,
  ReturnRequest,
  ShipRequest,
  TimelineEntry,
} from '../types/order'
import { api } from './client'

const BASE = '/api/v1/orders'
const path = (id: string) => `${BASE}/${encodeURIComponent(id)}`

/**
 * Thin wrappers around the order endpoints (docs/API.md §7). No shopId anywhere: the backend takes
 * it from the token. Every write returns the whole, updated order.
 */
export const ordersApi = {
  async list(params: OrderListParams): Promise<Page<OrderSummary>> {
    const { data } = await api.get<Page<OrderSummary>>(BASE, {
      params: Object.fromEntries(
        Object.entries(params).filter(([, v]) => v !== undefined && v !== '' && !(Array.isArray(v) && v.length === 0)),
      ),
      // ?status=PENDING&status=CONFIRMED (repeat the key), not Axios' default status[]=...
      paramsSerializer: { indexes: null },
    })
    return data
  },

  async get(id: string): Promise<Order> {
    const { data } = await api.get<Order>(path(id))
    return data
  },

  async timeline(id: string): Promise<TimelineEntry[]> {
    const { data } = await api.get<TimelineEntry[]>(`${path(id)}/timeline`)
    return data
  },

  /** 201 for a new order; 200 with the first order when the same requestId was sent before. */
  async create(body: CreateOrderRequest): Promise<Order> {
    const { data } = await api.post<Order>(BASE, body)
    return data
  },

  async confirm(id: string, note?: string): Promise<Order> {
    const { data } = await api.post<Order>(`${path(id)}/confirm`, { note })
    return data
  },

  async pack(id: string, note?: string): Promise<Order> {
    const { data } = await api.post<Order>(`${path(id)}/pack`, { note })
    return data
  },

  async ship(id: string, body: ShipRequest): Promise<Order> {
    const { data } = await api.post<Order>(`${path(id)}/ship`, body)
    return data
  },

  async deliver(id: string, note?: string): Promise<Order> {
    const { data } = await api.post<Order>(`${path(id)}/deliver`, { note })
    return data
  },

  async returnOrder(id: string, body: ReturnRequest): Promise<Order> {
    const { data } = await api.post<Order>(`${path(id)}/return`, body)
    return data
  },

  async cancel(id: string, reason: string): Promise<Order> {
    const { data } = await api.post<Order>(`${path(id)}/cancel`, { reason })
    return data
  },

  async bulkStatus(body: BulkStatusRequest): Promise<BulkStatusResponse> {
    const { data } = await api.post<BulkStatusResponse>(`${BASE}/bulk-status`, body)
    return data
  },
}
