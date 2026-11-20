import { fieldErrorsOf } from '../../lib/apiError'
import type { RowErrors, RowField } from './variantRows'

/** Product fields as edited in the form (all text; empty = not set). */
export interface ProductFieldsValue {
  name: string
  description: string
  category: string
}

export type ProductFieldErrors = Partial<Record<keyof ProductFieldsValue, string>>

/** Client-side checks with the same limits as the backend. */
export function validateProductFields(v: ProductFieldsValue): ProductFieldErrors {
  const e: ProductFieldErrors = {}
  if (!v.name.trim()) e.name = 'Enter a product name'
  else if (v.name.trim().length > 200) e.name = 'At most 200 characters'
  if (v.description.length > 5000) e.description = 'At most 5000 characters'
  if (v.category.trim().length > 50) e.category = 'At most 50 characters'
  return e
}

/**
 * Splits backend fieldErrors of a create request into product-field errors and row errors:
 * "name" → name, "variants[2].sku" → sku of the 3rd row.
 */
export function splitCreateErrors(error: unknown, rowKeys: string[]): { fields: ProductFieldErrors; rows: Record<string, RowErrors> } {
  const fields: ProductFieldErrors = {}
  const rows: Record<string, RowErrors> = {}
  for (const [path, message] of Object.entries(fieldErrorsOf(error))) {
    const match = /^variants\[(\d+)]\.?(\w+)?/.exec(path)
    if (match) {
      const key = rowKeys[Number(match[1])]
      if (!key) continue
      const field = (ROW_FIELDS as readonly string[]).includes(match[2] ?? '') ? (match[2] as RowField) : 'row'
      rows[key] = { ...rows[key], [field]: message }
    } else if (path === 'name' || path === 'description' || path === 'category') {
      fields[path] = message
    }
  }
  return { fields, rows }
}

/** Errors of a single-variant request (add / edit): "sku", "price"... → that row. */
export function rowErrorsOf(error: unknown): RowErrors {
  const result: RowErrors = {}
  for (const [path, message] of Object.entries(fieldErrorsOf(error))) {
    const field = (ROW_FIELDS as readonly string[]).includes(path) ? (path as RowField) : 'row'
    result[field] = result[field] ? `${result[field]}; ${message}` : message
  }
  return result
}

const ROW_FIELDS = ['sku', 'name', 'price', 'costPrice', 'lowStockThreshold'] as const
