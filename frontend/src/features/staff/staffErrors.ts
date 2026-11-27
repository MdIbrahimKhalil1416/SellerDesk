import axios from 'axios'
import { errorMessage, getApiError } from '../../lib/apiError'

/**
 * User-facing text per backend error code (docs/modules/STAFF.md §4). Switch on `code`, never on
 * the server's message.
 */
export const STAFF_ERROR_MESSAGES: Record<string, string> = {
  ALREADY_A_MEMBER: 'This person is already a member of your shop.',
  ALREADY_INVITED: 'This email already has a pending invitation. Use "Resend" in the list if they did not get it.',
  EMAIL_ALREADY_REGISTERED: 'This email already has a SellerDesk account in another shop, so it cannot be invited.',
  INVITE_RESEND_TOO_SOON: 'The invitation was just sent. Please wait a minute before sending it again.',
  INVITE_RESEND_LIMIT: 'This invitation was sent 5 times in the last 24 hours. Please try again tomorrow.',
  INVITATION_NOT_PENDING: 'This invitation was already accepted or cancelled. The list has been refreshed.',
  OWNER_READ_ONLY: 'The shop owner cannot be changed.',
  CANNOT_MODIFY_SELF: 'You cannot change your own role, permissions or status.',
  PERMISSION_NOT_GRANTABLE: 'You can only give permissions that you have yourself. Ask the shop owner to grant the others.',
  CONCURRENT_MODIFICATION: 'Someone else changed this staff member at the same time. Close and reopen to see the latest version.',
  NOT_FOUND: 'This staff member or invitation no longer exists. The list has been refreshed.',
  FORBIDDEN: 'You do not have permission to do this.',
  VALIDATION_FAILED: 'Some fields are not valid. Please check the highlighted fields.',
}

export function staffErrorMessage(error: unknown, fallback?: string): string {
  const apiError = getApiError(error)
  if (apiError?.code === 'INVITE_RESEND_TOO_SOON') {
    const seconds = retryAfterSeconds(error)
    if (seconds) return `The invitation was just sent. You can send it again in ${seconds} seconds.`
  }
  const known = apiError && STAFF_ERROR_MESSAGES[apiError.code]
  if (known) return known
  return errorMessage(error, fallback)
}

export function staffErrorCode(error: unknown): string | undefined {
  return getApiError(error)?.code
}

/** The `Retry-After` header of a 429 (seconds), or null. */
function retryAfterSeconds(error: unknown): number | null {
  if (!axios.isAxiosError(error)) return null
  const value = Number(error.response?.headers['retry-after'])
  return Number.isFinite(value) && value > 0 ? Math.ceil(value) : null
}
