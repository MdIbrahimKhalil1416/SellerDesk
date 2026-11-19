/**
 * The one error format every backend endpoint returns (docs/ARCHITECTURE.md §5).
 * Switch on `code`, never on `message`: messages may change, codes are stable.
 */
export interface ApiError {
  timestamp: string
  status: number
  /** e.g. VALIDATION_FAILED, INVALID_CREDENTIALS, TOKEN_EXPIRED, ORDER_NOT_FOUND */
  code: string
  /** Safe to show to the user. */
  message: string
  path: string
  /** Quote this in bug reports: it matches a line in the server log. */
  traceId: string
  /** Only filled for VALIDATION_FAILED. */
  fieldErrors: ApiFieldError[]
}

export interface ApiFieldError {
  /** Dotted path of the request field, e.g. "password" or "address.line" or "items[0].quantity". */
  field: string
  message: string
}

/** Page object returned by list endpoints. */
export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
