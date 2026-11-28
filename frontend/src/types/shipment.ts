import type { Actor } from './order'
import type { Address } from './shop'

/** Mirrors com.sellerdesk.courier.model.CourierCode. Phase 2 has only the mock. */
export type CourierCode = 'MOCK'

/** Mirrors com.sellerdesk.courier.model.ShipmentStatus (docs/modules/COURIER.md §3). */
export type ShipmentStatus = 'BOOKING' | 'CREATED' | 'PICKED_UP' | 'IN_TRANSIT' | 'DELIVERED' | 'RETURNED' | 'CANCELLED' | 'FAILED'

/** Mirrors com.sellerdesk.courier.model.EventSource. */
export type EventSource = 'USER' | 'SYSTEM' | 'WEBHOOK' | 'POLL'

/** What the current user may do with a shipment now (computed by the server). */
export type ShipmentAction = 'TRACK' | 'CANCEL'

export interface ShipmentEvent {
  at: string
  source: EventSource
  /** Our status after the event; null when it changed nothing (e.g. a location update). */
  status: ShipmentStatus | null
  /** The courier's own status text; null for our own events. */
  providerStatus: string | null
  /** An illegal status jump: stored for debugging, changed nothing. */
  ignored: boolean
  note: string | null
}

/** GET /shipments/{id} and the answer of every shipment write. Money in POISHA. */
export interface Shipment {
  id: string
  orderId: string
  orderNumber: string
  provider: CourierCode
  consignmentId: string | null
  trackingCode: string | null
  status: ShipmentStatus
  codAmount: number
  collectedAmount: number | null
  codMismatch: boolean
  courierFee: number | null
  recipient: { name: string; phone: string; address: Address }
  note: string | null
  failureReason: string | null
  /** We gave up without knowing whether the courier created the parcel. */
  needsManualCheck: boolean
  cancelReason: string | null
  /** Oldest first. */
  events: ShipmentEvent[]
  allowedActions: ShipmentAction[]
  createdBy: Actor
  createdAt: string
  updatedAt: string
}

/** A list row: GET /shipments and GET /orders/{id}/shipments. */
export interface ShipmentSummary {
  id: string
  orderId: string
  orderNumber: string
  provider: CourierCode
  consignmentId: string | null
  trackingCode: string | null
  status: ShipmentStatus
  codAmount: number
  collectedAmount: number | null
  codMismatch: boolean
  failureReason: string | null
  needsManualCheck: boolean
  createdAt: string
  updatedAt: string
}

export interface ShipmentListParams {
  status?: ShipmentStatus
  provider?: CourierCode
  page: number
  size: number
}

/** POST /orders/{id}/shipment. No shopId: the backend takes it from the token. */
export interface SendShipmentRequest {
  provider: CourierCode
  /** ≤ 200, instructions for the rider. */
  note?: string
}

/** POST /shipments/bulk: 1–20 PACKED orders per request. */
export interface BulkShipmentRequest {
  provider: CourierCode
  orderIds: string[]
}

export interface BulkShipmentResult {
  orderId: string
  orderNumber?: string | null
  /** BOOKING counts as ok: it finishes by itself. */
  ok: boolean
  shipmentId?: string | null
  status?: ShipmentStatus | null
  errorCode?: string | null
  message?: string | null
}

export interface BulkShipmentResponse {
  provider: CourierCode
  succeeded: number
  failed: number
  results: BulkShipmentResult[]
}
