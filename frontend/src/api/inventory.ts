import type { Page } from '../types/api'
import type {
  AdjustmentRequest,
  InventoryListParams,
  Movement,
  MovementListParams,
  RestockRequest,
  StockChangeResponse,
  StockRow,
  VariantStockDetail,
} from '../types/inventory'
import { api } from './client'

const BASE = '/api/v1/inventory'
const variantPath = (variantId: string) => `${BASE}/variants/${encodeURIComponent(variantId)}`

/** Leaves out empty values, so the backend uses its defaults and the URL stays short. */
function clean(params: object): Record<string, string | number | boolean> {
  return Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== '' && v !== null))
}

/**
 * Thin wrappers around the inventory endpoints (docs/API.md §5). No shopId anywhere: the backend
 * takes it from the token. Reserve / release / deduct have no endpoint (order status changes do them).
 */
export const inventoryApi = {
  async list(params: InventoryListParams): Promise<Page<StockRow>> {
    const { data } = await api.get<Page<StockRow>>(BASE, { params: clean(params) })
    return data
  },

  async lowStock(page: number, size: number): Promise<Page<StockRow>> {
    const { data } = await api.get<Page<StockRow>>(`${BASE}/low-stock`, { params: { page, size } })
    return data
  },

  async variant(variantId: string): Promise<VariantStockDetail> {
    const { data } = await api.get<VariantStockDetail>(variantPath(variantId))
    return data
  },

  async movements(params: MovementListParams): Promise<Page<Movement>> {
    const { data } = await api.get<Page<Movement>>(`${BASE}/movements`, { params: clean(params) })
    return data
  },

  async restock(variantId: string, body: RestockRequest): Promise<StockChangeResponse> {
    const { data } = await api.post<StockChangeResponse>(`${variantPath(variantId)}/restock`, body)
    return data
  },

  async adjust(variantId: string, body: AdjustmentRequest): Promise<StockChangeResponse> {
    const { data } = await api.post<StockChangeResponse>(`${variantPath(variantId)}/adjustments`, body)
    return data
  },
}
