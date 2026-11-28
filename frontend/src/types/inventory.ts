import type { ProductStatus } from './product'

/** Mirrors com.sellerdesk.inventory.service.StockStatus. */
export type StockStatus = 'IN_STOCK' | 'LOW' | 'OUT'

/** Where the low-stock threshold comes from: the variant's own value or the shop default. */
export type ThresholdSource = 'VARIANT' | 'SHOP'

/** Filter of GET /inventory. */
export type StockStatusFilter = 'ALL' | 'LOW' | 'OUT' | 'LOW_OR_OUT'

export type InventorySort = 'name,asc' | 'available,asc'

/** Mirrors com.sellerdesk.inventory.model.MovementType. */
export type MovementType = 'INITIAL' | 'RESTOCK' | 'ADJUSTMENT' | 'RESERVE' | 'RELEASE' | 'DEDUCT' | 'RETURN'

/** Mirrors com.sellerdesk.inventory.model.AdjustmentReason. */
export type AdjustmentReason = 'DAMAGED' | 'LOST' | 'FOUND' | 'COUNT_CORRECTION' | 'OTHER'

/** One variant in the stock list (docs/API.md §5). */
export interface StockRow {
  productId: string
  productName: string
  coverImageUrl: string | null
  productStatus: ProductStatus
  variantId: string
  sku: string
  variantName: string
  active: boolean
  onHand: number
  reserved: number
  /** onHand − reserved: what can still be sold. */
  available: number
  /** The threshold that applies (own or shop default). */
  lowStockThreshold: number
  thresholdSource: ThresholdSource
  stockStatus: StockStatus
}

export interface StockLevels {
  onHand: number
  reserved: number
  available: number
}

/** One entry of the movement log. Deltas are signed. */
export interface Movement {
  id: string
  productId: string
  variantId: string
  sku: string
  variantName: string
  type: MovementType
  onHandDelta: number
  reservedDelta: number
  onHandAfter: number
  reservedAfter: number
  availableAfter: number
  reasonCode: AdjustmentReason | null
  note: string | null
  orderId: string | null
  orderNumber: string | null
  createdBy: { id: string; name: string }
  createdAt: string
}

export interface VariantStockDetail {
  row: StockRow
  recentMovements: Movement[]
}

export interface StockChangeResponse {
  movement: Movement
  stock: StockLevels
}

export interface InventoryListParams {
  q?: string
  stockStatus: StockStatusFilter
  includeInactive: boolean
  sort: InventorySort
  page: number
  size: number
}

export interface MovementListParams {
  variantId?: string
  productId?: string
  type?: MovementType
  page: number
  size: number
}

/** POST /inventory/variants/{variantId}/restock. No shopId, no type: the server decides. */
export interface RestockRequest {
  quantity: number
  note: string | null
  /** New UUID per distinct submit; a repeat with the same id is applied only once. */
  requestId: string
}

/** POST /inventory/variants/{variantId}/adjustments. */
export interface AdjustmentRequest {
  /** Signed, never 0. */
  quantityChange: number
  reasonCode: AdjustmentReason
  /** Required for OTHER. */
  note: string | null
  requestId: string
}
