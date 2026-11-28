import { parseTakaToPoisha, poishaToTakaInput } from '../../lib/money'
import type { Address } from '../../types/shop'
import type { CustomerAddress } from '../../types/customer'
import type { OrderItemRequest } from '../../types/order'
import type { PickedVariant } from './components/ProductPicker'

/** Backend limits (docs/modules/ORDER.md §2). */
export const MAX_ITEMS = 50
export const MAX_QUANTITY = 999
export const MAX_NOTE = 1000
export const MAX_DELIVERY_CHARGE = 500_000

/** One line of the form: text while typing, converted on submit. */
export interface LineForm extends PickedVariant {
  quantity: string
  /** Taka, as typed. Starts as the variant price. */
  unitPrice: string
}

export function newLine(v: PickedVariant): LineForm {
  return { ...v, quantity: '1', unitPrice: poishaToTakaInput(v.price) }
}

/** Whole number 1…999, else null. */
export function parseQuantity(text: string): number | null {
  if (!/^\d{1,3}$/.test(text.trim())) return null
  const n = Number(text.trim())
  return n >= 1 && n <= MAX_QUANTITY ? n : null
}

/** Empty → 0 (for optional money inputs), invalid → null. */
export function parseOptionalTaka(text: string): number | null {
  return text.trim() === '' ? 0 : parseTakaToPoisha(text)
}

/**
 * The same arithmetic as the server (OrderRules.totals), for the live preview only. The server
 * computes the totals again on save; what it answers is what counts.
 */
export function previewTotals(lines: LineForm[], discount: number, deliveryCharge: number, advancePaid: number) {
  let subtotal = 0
  for (const line of lines) {
    const q = parseQuantity(line.quantity)
    const p = parseTakaToPoisha(line.unitPrice)
    if (q !== null && p !== null) subtotal += q * p
  }
  const total = subtotal - discount + deliveryCharge
  return { subtotal, discount, deliveryCharge, total, advancePaid, codAmount: total - advancePaid }
}

export function itemRequests(lines: LineForm[]): OrderItemRequest[] {
  return lines.map((line) => {
    const unitPrice = parseTakaToPoisha(line.unitPrice) ?? line.price
    // Only send a price when it was changed: otherwise the server uses the variant price itself.
    return { variantId: line.variantId, quantity: parseQuantity(line.quantity) ?? 1, ...(unitPrice !== line.price ? { unitPrice } : {}) }
  })
}

export function savedAddress(a: CustomerAddress): Address {
  return { line: a.line, area: a.area, district: a.district, zone: a.zone, note: a.note }
}
