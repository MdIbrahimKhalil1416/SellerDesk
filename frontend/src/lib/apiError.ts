import axios from 'axios'
import type { ApiError } from '../types/api'

/** True if `value` has the backend's ApiError shape. */
export function isApiError(value: unknown): value is ApiError {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as ApiError).code === 'string' &&
    typeof (value as ApiError).status === 'number' &&
    Array.isArray((value as ApiError).fieldErrors)
  )
}

/** The ApiError inside an Axios error, or null (network error, timeout, non-JSON body...). */
export function getApiError(error: unknown): ApiError | null {
  if (axios.isAxiosError(error) && isApiError(error.response?.data)) {
    return error.response.data
  }
  return null
}

/** A message that is always safe to show. */
export function errorMessage(error: unknown, fallback = 'Something went wrong. Please try again.'): string {
  const apiError = getApiError(error)
  if (apiError) return apiError.message
  if (axios.isAxiosError(error) && !error.response) return 'Cannot reach the server. Check your internet connection.'
  return fallback
}

/**
 * Turns a VALIDATION_FAILED response into `{ formField: message }` for showing under inputs.
 *
 * - Backend field paths are used as-is ("email", "address.line"), so name form fields the same
 *   way as the request DTO. Use `rename` where they differ: `{ newPassword: 'password' }`.
 * - If one field has several errors (e.g. "must contain a letter" and "must contain a digit"),
 *   they are joined into one message.
 * - Returns {} for any other error.
 *
 * @example
 *   catch (e) { setErrors(fieldErrorsOf(e)) }   // errors.password === "must contain at least one digit"
 */
export function fieldErrorsOf(error: unknown, rename: Record<string, string> = {}): Record<string, string> {
  const apiError = getApiError(error)
  const result: Record<string, string> = {}
  if (!apiError) return result
  for (const { field, message } of apiError.fieldErrors) {
    const name = rename[field] ?? field
    result[name] = result[name] ? `${result[name]}; ${message}` : message
  }
  return result
}
