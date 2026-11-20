/**
 * Money helpers. The API uses integer POISHA (৳1 = 100 poisha, docs/modules/PRODUCT.md §1);
 * people type and read TAKA.
 *
 * Why no `parseFloat(x) * 100`: floating point cannot store 0.1 exactly, so
 * `parseFloat('4.35') * 100` is 434.99999999999994. We split the text at the dot and work with
 * whole numbers only.
 */

/** ৳10 lakh, the backend's maximum. */
export const MAX_POISHA = 100_000_000

/**
 * "450" → 45000, "450.5" → 45050, "1,250.75" → 125075, "৳ 99" → 9900.
 * Returns null for anything that is not a non-negative amount with at most 2 decimals.
 */
export function parseTakaToPoisha(input: string): number | null {
  const text = input.trim().replace(/^৳\s*/, '').replace(/,/g, '')
  if (!/^\d+(\.\d{0,2})?$/.test(text)) return null
  const [whole = '0', fraction = ''] = text.split('.')
  const poisha = Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
  return Number.isSafeInteger(poisha) ? poisha : null
}

/** 45050 → "450.50", 45000 → "450" (for an input's value). null → "". */
export function poishaToTakaInput(poisha: number | null | undefined): string {
  if (poisha === null || poisha === undefined) return ''
  const whole = Math.floor(poisha / 100)
  const rest = poisha % 100
  return rest === 0 ? String(whole) : `${whole}.${String(rest).padStart(2, '0')}`
}

const grouping = new Intl.NumberFormat('en-IN') // lakh grouping: 1,00,000

/** 12345678 → "৳1,23,456.78", 45000 → "৳450". null → "—". */
export function formatTaka(poisha: number | null | undefined): string {
  if (poisha === null || poisha === undefined) return '—'
  const whole = Math.floor(poisha / 100)
  const rest = poisha % 100
  return `৳${grouping.format(whole)}${rest === 0 ? '' : `.${String(rest).padStart(2, '0')}`}`
}

/** "৳100 – ৳450" or "৳450" when equal; "—" when there is no active variant. */
export function formatPriceRange(min: number | null, max: number | null): string {
  if (min === null || max === null) return '—'
  return min === max ? formatTaka(min) : `${formatTaka(min)} – ${formatTaka(max)}`
}
