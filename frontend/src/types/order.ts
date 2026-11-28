import type { Address, DeliveryZone } from './shop'

/** Mirrors com.sellerdesk.order.model.OrderStatus (docs/modules/ORDER.md §4). */
export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'PACKED' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED' | 'RETURNED'

/** Mirrors com.sellerdesk.order.model.OrderSource. */
export type OrderSource = 'FACEBOOK' | 'MESSENGER' | 'WHATSAPP' | 'PHONE' | 'OTHER'

/** Mirrors com.sellerdesk.order.service.OrderAction: one button per action. */
export type OrderAction = 'CONFIRM' | 'PACK' | 'SHIP' | 'DELIVER' | 'RETURN' | 'CANCEL'

export interface Actor {
  id: string
  name: string
}

/** Item snapshot: name, SKU and price as they were when the item was added. Money in POISHA. */
export interface OrderItem {
  productId: string
  variantId: string
  sku: string
  productName: string
  variantName: string
  unitPrice: number
  quantity: number
  lineTotal: number
  /** Set on return: was this item put back on the shelf? */
  returnedRestocked: boolean | null
  /** Live stock; only with STOCK_VIEW and only while PENDING. */
  available?: number
}

export interface StatusChange {
  from: OrderStatus | null
  to: OrderStatus
  at: string
  by: Actor
  note: string | null
}

/** GET /orders/{id} and the answer of every order write (docs/API.md §7). */
export interface Order {
  id: string
  /** Display form, e.g. "SD-1042". */
  orderNumber: string
  status: OrderStatus
  source: OrderSource
  customer: { id: string; name: string; phone: string; address: Address }
  items: OrderItem[]
  subtotal: number
  discount: number
  deliveryCharge: number
  total: number
  advancePaid: number
  /** What the courier collects: total − advancePaid. */
  codAmount: number
  note: string | null
  shipping: { courierName: string | null; trackingId: string | null } | null
  cancelReason: string | null
  returnReason: string | null
  statusHistory: StatusChange[]
  /** What the current user may do now (legal for the status AND permitted). */
  allowedActions: OrderAction[]
  createdBy: Actor
  version: number
  createdAt: string
  updatedAt: string
}

/** One row of GET /orders. */
export interface OrderSummary {
  id: string
  orderNumber: string
  status: OrderStatus
  source: OrderSource
  customerId: string
  customerName: string
  customerPhone: string
  district: string | null
  zone: DeliveryZone | null
  /** Units (sum of quantities). */
  itemCount: number
  total: number
  codAmount: number
  createdAt: string
}

export type OrderSort = 'createdAt,desc' | 'createdAt,asc'

export interface OrderListParams {
  status?: OrderStatus[]
  source?: OrderSource
  /** YYYY-MM-DD, Dhaka day, inclusive. */
  from?: string
  to?: string
  /** Order number (SD-1042, 1042) or customer phone. */
  q?: string
  sort: OrderSort
  page: number
  size: number
}

export interface OrderItemRequest {
  variantId: string
  quantity: number
  /** Poisha; left out = the variant's current price. */
  unitPrice?: number
}

/** POST /orders. Exactly one of customerId / customer. The server computes every total. */
export interface CreateOrderRequest {
  requestId: string
  customerId?: string
  customer?: { name: string; phone: string; address?: Address }
  deliveryAddress?: Address
  items: OrderItemRequest[]
  deliveryCharge?: number
  discount?: number
  advancePaid?: number
  source: OrderSource
  note?: string
}

export interface BulkStatusRequest {
  action: Exclude<OrderAction, 'RETURN'>
  orderIds: string[]
  note?: string
  reason?: string
  courierName?: string
}

export interface BulkStatusResult {
  orderId: string
  orderNumber?: string
  ok: boolean
  status?: OrderStatus
  errorCode?: string
  message?: string
}

export interface BulkStatusResponse {
  action: OrderAction
  succeeded: number
  failed: number
  results: BulkStatusResult[]
}

export interface TimelineEntry {
  type: 'STATUS' | 'EDIT'
  at: string
  by: Actor
  from?: OrderStatus
  to?: OrderStatus
  note?: string
  fields?: string[]
  summary?: string
}

export interface ReturnRequest {
  reason: string
  /** Only exceptions: an item left out is restocked. */
  items?: Array<{ variantId: string; restock: boolean }>
}

export interface ShipRequest {
  courierName?: string
  trackingId?: string
  note?: string
}
