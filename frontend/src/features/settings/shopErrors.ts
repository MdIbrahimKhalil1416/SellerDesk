import { errorMessage, getApiError, httpStatusOf } from '../../lib/apiError'

/** User-facing text per backend error code (docs/API.md §2). Switch on `code`, never on the message. */
const SHOP_ERROR_MESSAGES: Record<string, string> = {
  VALIDATION_FAILED: 'Some fields are not valid. Please check the highlighted fields.',
  IMAGE_TOO_LARGE: 'The logo is larger than 5 MB. Choose a smaller image or resize it.',
  IMAGE_TYPE_NOT_ALLOWED: 'Only JPEG, PNG and WebP images are allowed.',
  IMAGE_UPLOAD_FAILED: 'The logo could not be uploaded right now. Please try again.',
  FORBIDDEN: 'You do not have permission to change the shop settings.',
}

export function shopErrorMessage(error: unknown, fallback?: string): string {
  const apiError = getApiError(error)
  const known = apiError && SHOP_ERROR_MESSAGES[apiError.code]
  if (known) return known
  // A proxy may reject a huge upload with a plain-text 413 before it reaches the backend.
  if (!apiError && httpStatusOf(error) === 413) return SHOP_ERROR_MESSAGES.IMAGE_TOO_LARGE ?? ''
  return errorMessage(error, fallback)
}
