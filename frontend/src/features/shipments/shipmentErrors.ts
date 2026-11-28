import { errorMessage, getApiError } from '../../lib/apiError'

/**
 * User-facing text per backend error code (docs/modules/COURIER.md §4). Switch on `code`.
 * COURIER_REJECTED is left out on purpose: its message is the courier's own reason.
 */
const SHIPMENT_ERROR_MESSAGES: Record<string, string> = {
  ORDER_NOT_PACKED: 'Only packed orders can be sent to a courier. Reload to see the current status of the order.',
  SHIPMENT_ALREADY_ACTIVE: 'This order is already booked with another courier. Cancel that shipment first.',
  SHIPMENT_NOT_CANCELLABLE: 'The rider has already picked up the parcel, so the booking can no longer be cancelled.',
  COURIER_UNAVAILABLE: 'The courier did not answer. Nothing was changed; please try again in a minute.',
  COURIER_NOT_AVAILABLE: 'This courier is not available right now.',
  NOT_FOUND: 'This order or shipment no longer exists.',
  FORBIDDEN: 'You do not have permission to do this.',
  VALIDATION_FAILED: 'Some fields are not valid. Please check them and try again.',
}

export function shipmentErrorMessage(error: unknown, fallback?: string): string {
  const apiError = getApiError(error)
  const known = apiError ? SHIPMENT_ERROR_MESSAGES[apiError.code] : undefined
  return known ?? errorMessage(error, fallback)
}

/** Friendly names for the order fields a courier can reject (fieldErrors of COURIER_REJECTED). */
const FIELD_LABELS: Record<string, string> = {
  'recipient.name': 'Customer name',
  'recipient.phone': 'Phone',
  'recipient.address': 'Address',
  'recipient.address.line': 'Address',
  'recipient.address.area': 'Area',
  'recipient.address.district': 'District',
}

/**
 * For 409 COURIER_REJECTED: "Phone: Invalid phone number" per field the courier named, so the
 * user knows what to fix in the order before sending again.
 */
export function rejectedFieldsOf(error: unknown): string[] {
  const apiError = getApiError(error)
  if (apiError?.code !== 'COURIER_REJECTED') return []
  return apiError.fieldErrors.map(({ field, message }) => `${FIELD_LABELS[field] ?? field}: ${message}`)
}
