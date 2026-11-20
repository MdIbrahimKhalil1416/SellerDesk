import { errorMessage, getApiError, httpStatusOf } from '../../lib/apiError'

/**
 * User-facing text per backend error code (docs/modules/PRODUCT.md §6). Switch on `code`, never
 * on the server's message.
 */
export const PRODUCT_ERROR_MESSAGES: Record<string, string> = {
  SKU_ALREADY_EXISTS: 'This SKU is already used by another product in your shop. Choose a different SKU.',
  DUPLICATE_VARIANT_ATTRIBUTES: 'Two active variants have the same options (for example two "Red / M"). Change or deactivate one of them.',
  VARIANT_LIMIT_REACHED: 'A product can have at most 100 variants.',
  IMAGE_LIMIT_REACHED: 'A product can have at most 8 images. Remove one first.',
  IMAGE_TOO_LARGE: 'The image is larger than 5 MB. Choose a smaller photo or resize it.',
  IMAGE_TYPE_NOT_ALLOWED: 'Only JPEG, PNG and WebP images are allowed.',
  IMAGE_UPLOAD_FAILED: 'The image could not be uploaded right now. Please try again.',
  CONCURRENT_MODIFICATION: 'Someone else changed this product in the meantime. Reload the page to see the latest version, then try again.',
  NOT_FOUND: 'This product no longer exists.',
  FORBIDDEN: 'You do not have permission to do this.',
  VALIDATION_FAILED: 'Some fields are not valid. Please check the highlighted fields.',
}

export function productErrorMessage(error: unknown, fallback?: string): string {
  const apiError = getApiError(error)
  const known = apiError && PRODUCT_ERROR_MESSAGES[apiError.code]
  if (known) return known
  // A proxy may reject a huge upload with a plain-text 413 before it reaches the backend.
  if (!apiError && httpStatusOf(error) === 413) return PRODUCT_ERROR_MESSAGES.IMAGE_TOO_LARGE ?? ''
  return errorMessage(error, fallback)
}

export function errorCodeOf(error: unknown): string | undefined {
  return getApiError(error)?.code
}
