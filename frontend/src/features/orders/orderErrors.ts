import { errorMessage, getApiError } from '../../lib/apiError'

/** User-facing text per backend error code (docs/modules/ORDER.md §8). Switch on `code`. */
const ORDER_ERROR_MESSAGES: Record<string, string> = {
  INVALID_STATUS_TRANSITION: 'This order has already moved on. Reload to see its current status.',
  SHIPMENT_ACTIVE: 'This order is booked with a courier. Cancel the courier booking first.',
  ORDER_NOT_EDITABLE:'This order can no longer be changed.',
  VARIANT_NOT_ORDERABLE: 'Some products are archived or inactive and cannot be ordered. Remove them.',
  REQUEST_ID_REUSED: 'This form was already sent with different content. Reload the page and try again.',
  CONCURRENT_MODIFICATION: 'Someone else changed this order in the meantime. Reload and try again.',
  NOT_FOUND: 'This order, customer or product no longer exists.',
  FORBIDDEN: 'You do not have permission to do this.',
  VALIDATION_FAILED: 'Some fields are not valid. Please check the highlighted fields.',
}

/** INSUFFICIENT_STOCK is left to the caller: its message names the short SKUs. */
export function orderErrorMessage(error: unknown, fallback?: string): string {
  const apiError = getApiError(error)
  const known = apiError ? ORDER_ERROR_MESSAGES[apiError.code] : undefined
  return known ?? errorMessage(error, fallback)
}

export function errorCodeOf(error: unknown): string | undefined {
  return getApiError(error)?.code
}

/**
 * For a 409 INSUFFICIENT_STOCK on confirm: item index → "TEE-M: requested 3, available 1".
 * The backend sends one field error per short item (`items[2].quantity`).
 */
export function shortItemsOf(error: unknown): Map<number, string> {
  const result = new Map<number, string>()
  const apiError = getApiError(error)
  if (apiError?.code !== 'INSUFFICIENT_STOCK') return result
  for (const { field, message } of apiError.fieldErrors) {
    const match = /^items\[(\d+)]\.quantity$/.exec(field)
    if (match) result.set(Number(match[1]), message)
  }
  return result
}
