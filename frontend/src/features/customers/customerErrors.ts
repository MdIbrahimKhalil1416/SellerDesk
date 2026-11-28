import { errorMessage, getApiError } from '../../lib/apiError'

/** User-facing text per backend error code (docs/modules/CUSTOMER.md §4). Switch on `code`. */
const CUSTOMER_ERROR_MESSAGES: Record<string, string> = {
  PHONE_ALREADY_EXISTS: 'Another customer of your shop already has this phone number.',
  ADDRESS_LIMIT_REACHED: 'A customer can have at most 5 addresses. Remove one first.',
  CONCURRENT_MODIFICATION: 'Someone else changed this customer in the meantime. Reload the page and try again.',
  NOT_FOUND: 'This customer no longer exists.',
  FORBIDDEN: 'You do not have permission to do this.',
  VALIDATION_FAILED: 'Some fields are not valid. Please check the highlighted fields.',
}

export function customerErrorMessage(error: unknown, fallback?: string): string {
  const apiError = getApiError(error)
  const known = apiError ? CUSTOMER_ERROR_MESSAGES[apiError.code] : undefined
  return known ?? errorMessage(error, fallback)
}

export function errorCodeOf(error: unknown): string | undefined {
  return getApiError(error)?.code
}
