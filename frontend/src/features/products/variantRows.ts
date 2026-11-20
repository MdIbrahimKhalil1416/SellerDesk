import { MAX_POISHA, parseTakaToPoisha, poishaToTakaInput } from '../../lib/money'
import type { UpdateVariantRequest, Variant, VariantRequest, VariantStock } from '../../types/product'

/**
 * Pure logic of the variant editor: option combinations, SKU suggestions, validation and the
 * conversion between form rows (taka as text) and API objects (poisha as integers).
 */

export const MAX_VARIANTS = 100
export const MAX_OPTIONS = 5
export const MAX_LOW_STOCK_THRESHOLD = 100_000
/** Same rule as the backend (after trimming; lowercase is uppercased by the server). */
export const SKU_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/

/** One option with its values, e.g. { name: 'Size', values: ['S', 'M', 'L'] }. */
export interface VariantOption {
  name: string
  values: string[]
}

/** One variant as edited in the form. Money fields hold TAKA text, e.g. "450.50". */
export interface VariantRow {
  /** Stable React key: the variantId, or "new-3" for an unsaved row. */
  key: string
  /** Set once the variant exists on the server. Its SKU can then no longer change. */
  variantId?: string
  sku: string
  name: string
  attributes: Record<string, string>
  price: string
  costPrice: string
  lowStockThreshold: string
  active: boolean
  stock?: VariantStock
}

export type RowField = 'sku' | 'name' | 'price' | 'costPrice' | 'lowStockThreshold'
export type RowErrors = Partial<Record<RowField | 'row', string>>

let counter = 0
const newKey = () => `new-${++counter}`

export function emptyRow(attributes: Record<string, string> = {}, sku = '', price = ''): VariantRow {
  return { key: newKey(), sku, name: '', attributes, price, costPrice: '', lowStockThreshold: '', active: true }
}

export function rowFromVariant(v: Variant): VariantRow {
  return {
    key: v.variantId,
    variantId: v.variantId,
    sku: v.sku,
    name: v.name,
    attributes: v.attributes,
    price: poishaToTakaInput(v.price),
    costPrice: poishaToTakaInput(v.costPrice),
    lowStockThreshold: v.lowStockThreshold === null ? '' : String(v.lowStockThreshold),
    active: v.active,
    stock: v.stock,
  }
}

/** "Red / M": what the backend uses as name when none is given. */
export function defaultVariantName(attributes: Record<string, string>): string {
  return Object.values(attributes).join(' / ')
}

/** Case- and order-independent: {Size: M, Color: Red} equals {color: red, size: m}. */
export function attributeSignature(attributes: Record<string, string>): string {
  return Object.entries(attributes)
    .map(([k, v]) => `${k.trim().toLowerCase()}=${v.trim().toLowerCase()}`)
    .sort()
    .join('|')
}

/** Every combination of the option values: Size [S, M] × Color [Red] → [{Size:S,Color:Red}, {Size:M,Color:Red}]. */
export function combinations(options: VariantOption[]): Array<Record<string, string>> {
  const usable = options
    .map((o) => ({ name: o.name.trim(), values: o.values.map((v) => v.trim()).filter(Boolean) }))
    .filter((o) => o.name && o.values.length > 0)
  if (usable.length === 0) return []
  let result: Array<Record<string, string>> = [{}]
  for (const option of usable) {
    result = result.flatMap((combo) => option.values.map((value) => ({ ...combo, [option.name]: value })))
  }
  return result
}

/** "Cotton Tee" + [Red, M] → "COTTON-TEE-RED-M" (only allowed characters, max 40). */
export function suggestSku(prefix: string, values: string[]): string {
  return [prefix, ...values]
    .join('-')
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
    .replace(/-+$/, '')
}

/**
 * Adds a row for each combination that does not exist yet (same options, any case/order).
 * An untouched empty row without options (the starting row of a new product) is replaced.
 */
export function generateRows(
  existing: VariantRow[],
  options: VariantOption[],
  skuPrefix: string,
  defaultPrice: string,
): { rows: VariantRow[]; added: number; overLimit: number } {
  const isPristineBlank = (r: VariantRow) =>
    !r.variantId && Object.keys(r.attributes).length === 0 && !r.sku.trim() && !r.price.trim() && !r.name.trim()
  const kept = existing.filter((r) => !isPristineBlank(r))
  const known = new Set(kept.map((r) => attributeSignature(r.attributes)))

  const added: VariantRow[] = []
  let overLimit = 0
  for (const combo of combinations(options)) {
    if (known.has(attributeSignature(combo))) continue
    if (kept.length + added.length >= MAX_VARIANTS) {
      overLimit++
      continue
    }
    known.add(attributeSignature(combo))
    added.push(emptyRow(combo, suggestSku(skuPrefix, Object.values(combo)), defaultPrice))
  }
  return { rows: [...kept, ...added], added: added.length, overLimit }
}

function parseThreshold(text: string): number | null | 'invalid' {
  const t = text.trim()
  if (!t) return null
  if (!/^\d+$/.test(t)) return 'invalid'
  const n = Number(t)
  return n <= MAX_LOW_STOCK_THRESHOLD ? n : 'invalid'
}

function moneyError(text: string, required: boolean): string | undefined {
  if (!text.trim()) return required ? 'Enter a price' : undefined
  const poisha = parseTakaToPoisha(text)
  if (poisha === null) return 'Enter an amount like 450 or 450.50'
  if (poisha > MAX_POISHA) return 'Must be at most ৳10,00,000'
  return undefined
}

/** Client-side checks before sending (the backend checks again). Returns errors by row key. */
export function validateRows(rows: VariantRow[]): Record<string, RowErrors> {
  const errors: Record<string, RowErrors> = {}
  const skuCount = new Map<string, number>()
  for (const r of rows) {
    const sku = r.sku.trim().toUpperCase()
    if (sku) skuCount.set(sku, (skuCount.get(sku) ?? 0) + 1)
  }
  for (const r of rows) {
    const e: RowErrors = {}
    const sku = r.sku.trim()
    if (!r.variantId) {
      if (!sku) e.sku = 'Enter a SKU'
      else if (!SKU_PATTERN.test(sku)) e.sku = "Letters, digits, '.', '_' or '-' only (max 40)"
    }
    if (sku && (skuCount.get(sku.toUpperCase()) ?? 0) > 1) e.sku = 'This SKU is used twice in this product'
    const price = moneyError(r.price, true)
    if (price) e.price = price
    const cost = moneyError(r.costPrice, false)
    if (cost) e.costPrice = cost
    if (parseThreshold(r.lowStockThreshold) === 'invalid') e.lowStockThreshold = 'Whole number, 0 – 100000'
    if (r.name.trim().length > 100) e.name = 'At most 100 characters'
    if (Object.keys(e).length > 0) errors[r.key] = e
  }
  return errors
}

/** For POST (create product / add variant). Call only after validateRows passed. */
export function toVariantRequest(row: VariantRow): VariantRequest {
  const cost = parseTakaToPoisha(row.costPrice)
  const threshold = parseThreshold(row.lowStockThreshold)
  return {
    sku: row.sku.trim(),
    name: row.name.trim() || undefined,
    attributes: Object.keys(row.attributes).length > 0 ? row.attributes : undefined,
    price: parseTakaToPoisha(row.price) ?? 0,
    costPrice: cost ?? undefined,
    lowStockThreshold: typeof threshold === 'number' ? threshold : undefined,
  }
}

/**
 * Only the fields that differ from the saved variant, for PATCH; null if nothing changed.
 * A cleared cost price or threshold counts as "unchanged": the API cannot remove them.
 */
export function changedFields(row: VariantRow, saved: Variant): Omit<UpdateVariantRequest, 'version'> | null {
  const changes: Omit<UpdateVariantRequest, 'version'> = {}
  if (row.name.trim() !== saved.name) changes.name = row.name.trim() // blank → back to the default name
  const price = parseTakaToPoisha(row.price)
  if (price !== null && price !== saved.price) changes.price = price
  const cost = parseTakaToPoisha(row.costPrice)
  if (cost !== null && cost !== (saved.costPrice ?? null)) changes.costPrice = cost
  const threshold = parseThreshold(row.lowStockThreshold)
  if (typeof threshold === 'number' && threshold !== saved.lowStockThreshold) changes.lowStockThreshold = threshold
  if (row.active !== saved.active) changes.active = row.active
  return Object.keys(changes).length > 0 ? changes : null
}

/** True if the row differs from the server (or is new). */
export function isDirty(row: VariantRow, saved: Variant | undefined): boolean {
  return !saved || changedFields(row, saved) !== null
}
