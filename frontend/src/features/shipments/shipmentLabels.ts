import type { CourierCode, EventSource, ShipmentStatus } from '../../types/shipment'

export const SHIPMENT_STATUS_LABELS: Record<ShipmentStatus, string> = {
  BOOKING: 'Booking…',
  CREATED: 'Booked',
  PICKED_UP: 'Picked up',
  IN_TRANSIT: 'In transit',
  DELIVERED: 'Delivered',
  RETURNED: 'Returned',
  CANCELLED: 'Cancelled',
  FAILED: 'Booking failed',
}

/** On the way to the customer first, then the dead ends; for the status tabs. */
export const SHIPMENT_STATUSES: ShipmentStatus[] = ['BOOKING', 'CREATED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'RETURNED', 'CANCELLED', 'FAILED']

export const SHIPMENT_STATUS_STYLES: Record<ShipmentStatus, string> = {
  BOOKING: 'bg-amber-50 text-amber-800 ring-amber-200',
  CREATED: 'bg-indigo-50 text-indigo-800 ring-indigo-200',
  PICKED_UP: 'bg-violet-50 text-violet-800 ring-violet-200',
  IN_TRANSIT: 'bg-violet-50 text-violet-800 ring-violet-200',
  DELIVERED: 'bg-emerald-50 text-emerald-800 ring-emerald-200',
  RETURNED: 'bg-red-50 text-red-800 ring-red-200',
  CANCELLED: 'bg-slate-100 text-slate-600 ring-slate-200',
  FAILED: 'bg-red-50 text-red-800 ring-red-200',
}

/**
 * Mirrors ShipmentStatus.active() in the backend: at most one active shipment per order. While
 * one is active the order cannot be shipped or cancelled by hand (409 SHIPMENT_ACTIVE).
 */
export function isActiveShipment(status: ShipmentStatus): boolean {
  return status === 'BOOKING' || status === 'CREATED' || status === 'PICKED_UP' || status === 'IN_TRANSIT'
}

/** Mirrors CourierCode.displayName(). A real courier is added here with its backend provider. */
export const COURIER_LABELS: Record<CourierCode, string> = {
  MOCK: 'Mock courier',
}

export const COURIER_OPTIONS = (Object.keys(COURIER_LABELS) as CourierCode[]).map((value) => ({ value, label: COURIER_LABELS[value] }))

export const EVENT_SOURCE_LABELS: Record<EventSource, string> = {
  USER: 'Staff',
  SYSTEM: 'SellerDesk',
  WEBHOOK: 'Courier',
  POLL: 'Tracking',
}

/** Backend limits (SendShipmentRequest.note, CancelShipmentRequest.reason, bulk size). */
export const MAX_NOTE = 200
export const MAX_REASON = 500
export const BULK_BATCH_SIZE = 20
