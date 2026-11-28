import { errorMessage, getApiError } from '../../lib/apiError'

/** User-facing text per backend error code (docs/modules/INVENTORY.md §5). Switch on `code`. */
const INVENTORY_ERROR_MESSAGES: Record<string, string> = {
  STOCK_LIMIT_EXCEEDED: 'A variant can have at most 10,00,000 units on hand.',
  REQUEST_ID_REUSED: 'This form was already used for a different change. Close it and try again.',
  NOT_FOUND: 'This variant no longer exists.',
  FORBIDDEN: 'You do not have permission to change stock.',
  VALIDATION_FAILED: 'Some fields are not valid. Please check the highlighted fields.',
  MALFORMED_REQUEST: 'Please enter whole numbers only.',
}

export function inventoryErrorMessage(error: unknown, fallback?: string): string {
  const apiError = getApiError(error)
  // The server's INSUFFICIENT_STOCK message names the SKU and the numbers and is written for users.
  if (apiError?.code === 'INSUFFICIENT_STOCK') return apiError.message
  const known = apiError ? INVENTORY_ERROR_MESSAGES[apiError.code] : undefined
  return known ?? errorMessage(error, fallback)
}
