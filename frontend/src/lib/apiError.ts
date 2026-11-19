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

/**
 * HTTP status of a failed request, or null if no response arrived (offline, timeout, CORS block).
 * Works even when the body is not an ApiError (e.g. a proxy's plain-text 429 or 502).
 */
export function httpStatusOf(error: unknown): number | null {
  return axios.isAxiosError(error) ? (error.response?.status ?? null) : null
}

export const NETWORK_ERROR_MESSAGE = 'Cannot reach the server. Check your internet connection and try again.'
export const TIMEOUT_MESSAGE = 'The server took too long to answer. Please try again.'
export const RATE_LIMITED_MESSAGE = 'Too many attempts. Please wait a minute and try again.'

/**
 * A message that is always safe to show.
 *
 * Order: the backend's own ApiError message first (it is written for users), then messages based
 * on what happened to the request (no response / timeout / 429 / 5xx), then `fallback`.
 */
export function errorMessage(error: unknown, fallback = 'Something went wrong. Please try again.'): string {
  const apiError = getApiError(error)
  if (apiError) return apiError.message
  if (!axios.isAxiosError(error)) return fallback
  if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') return TIMEOUT_MESSAGE
  if (!error.response) return NETWORK_ERROR_MESSAGE
  const status = error.response.status
  if (status === 429) return RATE_LIMITED_MESSAGE
  // 502/503/504 come from the proxy or Render while the backend is down or waking up.
  if (status >= 500) return 'The server is having a problem right now. Please try again in a minute.'
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
