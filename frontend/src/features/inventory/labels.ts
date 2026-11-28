import type { AdjustmentReason, MovementType, StockStatusFilter } from '../../types/inventory'

export const MOVEMENT_LABELS: Record<MovementType, string> = {
  INITIAL: 'Opening stock',
  RESTOCK: 'Restock',
  ADJUSTMENT: 'Adjustment',
  RESERVE: 'Reserved for order',
  RELEASE: 'Released (order cancelled)',
  DEDUCT: 'Shipped',
  RETURN: 'Returned',
}

export const REASON_LABELS: Record<AdjustmentReason, string> = {
  DAMAGED: 'Damaged',
  LOST: 'Lost / missing',
  FOUND: 'Found',
  COUNT_CORRECTION: 'Stock count correction',
  OTHER: 'Other (explain in the note)',
}

export const STOCK_FILTERS: Array<{ value: StockStatusFilter; label: string }> = [
  { value: 'ALL', label: 'All stock levels' },
  { value: 'LOW_OR_OUT', label: 'Low or out of stock' },
  { value: 'LOW', label: 'Low stock' },
  { value: 'OUT', label: 'Out of stock' },
]

/** "+5", "−3", "0" (a real minus sign reads better than a hyphen). */
export function signed(n: number): string {
  if (n > 0) return `+${n}`
  if (n < 0) return `−${Math.abs(n)}`
  return '0'
}

/**
 * A fresh idempotency key (docs/modules/INVENTORY.md §1). The dialogs keep one key while the
 * values stay the same (a retry after a timeout is then applied only once) and make a new one
 * when the user changes the form.
 */
export function newRequestId(): string {
  return crypto.randomUUID()
}

/** "28 Nov 2026, 10:15". */
export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })
}
