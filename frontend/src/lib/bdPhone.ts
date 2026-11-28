/**
 * Bangladeshi mobile numbers, the same rules as the backend's `common.phone.BdPhone`:
 *
 * - Spaces, dashes, dots and parentheses are ignored; Bangla digits ০-৯ count as 0-9.
 * - Accepted: 01XXXXXXXXX, +8801XXXXXXXXX, 8801XXXXXXXXX, 008801XXXXXXXXX.
 * - The digit after "01" (the operator) must be 3-9.
 *
 * The backend checks again and has the last word; this is for instant feedback.
 */

export const BD_PHONE_EXAMPLE = '01712345678'

const SEPARATORS = /[\s\-().]/g
const MOBILE = /^(?:\+880|880|00880|0)(1[3-9]\d{8})$/

function toAsciiDigits(text: string): string {
  return text.replace(/[০-৯]/g, (d) => String(d.charCodeAt(0) - 0x09e6))
}

/** "+880 1712-345678" → "01712345678". null if it is not a Bangladeshi mobile number. */
export function normalizeBdPhone(input: string): string | null {
  const compact = toAsciiDigits(input.trim()).replace(SEPARATORS, '')
  const match = MOBILE.exec(compact)
  return match ? `0${match[1]}` : null
}
