import { useId, type InputHTMLAttributes } from 'react'
import { TrashIcon } from '../../../components/icons'
import { cn } from '../../../lib/cn'
import type { Variant } from '../../../types/product'
import { defaultVariantName, isDirty, type RowErrors, type VariantRow } from '../variantRows'

interface Props {
  rows: VariantRow[]
  onRowsChange: (rows: VariantRow[]) => void
  errors: Record<string, RowErrors>
  readOnly?: boolean
  showCost: boolean
  showStock: boolean
  /** Saved variants by id (edit page), to mark rows with unsaved changes. */
  saved?: Map<string, Variant>
}

/**
 * Step 2: one row per variant with SKU, prices (in taka) and low-stock threshold.
 * Desktop: a table-like grid with one header row. Phones: one card per variant with labels.
 *
 * Saved variants keep their SKU forever and are never deleted, only deactivated (orders refer to
 * them). Unsaved rows can be removed.
 */
export function VariantTable({ rows, onRowsChange, errors, readOnly, showCost, showStock, saved }: Props) {
  const columns = cn(
    'md:grid md:items-start md:gap-3',
    showCost
      ? 'md:grid-cols-[minmax(8rem,1.3fr)_minmax(8rem,1.2fr)_minmax(6rem,0.9fr)_minmax(6rem,0.9fr)_minmax(5rem,0.7fr)_6.5rem]'
      : 'md:grid-cols-[minmax(8rem,1.3fr)_minmax(8rem,1.2fr)_minmax(6rem,0.9fr)_minmax(5rem,0.7fr)_6.5rem]',
  )

  function update(key: string, patch: Partial<VariantRow>) {
    onRowsChange(rows.map((r) => (r.key === key ? { ...r, ...patch } : r)))
  }

  if (rows.length === 0) {
    return <p className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-500">No variants yet. Generate them from options or add one.</p>
  }

  return (
    <div className="space-y-3 md:space-y-0 md:overflow-hidden md:rounded-xl md:border md:border-slate-200 md:bg-white">
      <div className={cn(columns, 'hidden border-b border-slate-200 bg-slate-50 px-4 py-2 text-xs font-medium uppercase tracking-wide text-slate-500 md:grid')}>
        <span>Variant</span>
        <span>SKU</span>
        <span>Price (৳)</span>
        {showCost && <span>Cost (৳)</span>}
        <span>Low stock at</span>
        <span className="text-right">{showStock ? 'Stock / Active' : 'Active'}</span>
      </div>

      {rows.map((row) => {
        const e = errors[row.key] ?? {}
        const savedVariant = row.variantId ? saved?.get(row.variantId) : undefined
        const unsaved = !readOnly && isDirty(row, savedVariant)
        const hasAttributes = Object.keys(row.attributes).length > 0
        return (
          <div
            key={row.key}
            className={cn(
              columns,
              'space-y-3 rounded-xl border bg-white p-4 md:space-y-0 md:rounded-none md:border-0 md:border-b md:border-slate-100 md:last:border-b-0',
              !row.active && 'bg-slate-50 opacity-75',
              e.row ? 'border-red-300' : 'border-slate-200',
            )}
          >
            {/* Variant: options as chips + optional custom name */}
            <div className="min-w-0">
              {hasAttributes && (
                <div className="mb-1.5 flex flex-wrap gap-1">
                  {Object.entries(row.attributes).map(([k, v]) => (
                    <span key={k} className="rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-700" title={k}>
                      {v}
                    </span>
                  ))}
                </div>
              )}
              <Cell
                label="Name"
                value={row.name}
                onChange={(ev) => update(row.key, { name: ev.target.value })}
                placeholder={hasAttributes ? defaultVariantName(row.attributes) : 'Default'}
                error={e.name}
                maxLength={100}
                disabled={readOnly}
              />
              {unsaved && savedVariant && <span className="mt-1 inline-block text-xs font-medium text-amber-600">Unsaved changes</span>}
              {unsaved && !savedVariant && <span className="mt-1 inline-block text-xs font-medium text-indigo-600">New</span>}
            </div>

            {row.variantId ? (
              <div>
                <span className="mb-1 block text-xs font-medium text-slate-500 md:sr-only">SKU</span>
                <p className="truncate py-2 font-mono text-sm text-slate-700" title="A saved SKU cannot be changed">
                  {row.sku}
                </p>
              </div>
            ) : (
              <Cell
                label="SKU"
                value={row.sku}
                onChange={(ev) => update(row.key, { sku: ev.target.value })}
                placeholder="TEE-RED-M"
                error={e.sku}
                maxLength={40}
                disabled={readOnly}
                className="font-mono uppercase"
                autoCapitalize="characters"
              />
            )}

            <Cell label="Price (৳)" inputMode="decimal" value={row.price} onChange={(ev) => update(row.key, { price: ev.target.value })} placeholder="0.00" error={e.price} disabled={readOnly} />
            {showCost && (
              <Cell
                label="Cost (৳)"
                inputMode="decimal"
                value={row.costPrice}
                onChange={(ev) => update(row.key, { costPrice: ev.target.value })}
                placeholder="optional"
                error={e.costPrice}
                disabled={readOnly}
              />
            )}
            <Cell
              label="Low stock at"
              inputMode="numeric"
              value={row.lowStockThreshold}
              onChange={(ev) => update(row.key, { lowStockThreshold: ev.target.value })}
              placeholder="Shop default"
              error={e.lowStockThreshold}
              disabled={readOnly}
            />

            <div className="flex items-center justify-between gap-2 md:justify-end md:pt-2">
              {showStock && row.stock && (
                <span className="text-xs text-slate-500" title="On hand / available">
                  {row.stock.onHand} / {row.stock.available}
                </span>
              )}
              {row.variantId ? (
                <label className="inline-flex items-center gap-2 text-sm text-slate-700">
                  <input
                    type="checkbox"
                    checked={row.active}
                    disabled={readOnly}
                    onChange={(ev) => update(row.key, { active: ev.target.checked })}
                    className="h-4 w-4 rounded border-slate-300 text-indigo-600"
                  />
                  <span className="md:sr-only">Active</span>
                </label>
              ) : (
                !readOnly && (
                  <button
                    type="button"
                    onClick={() => onRowsChange(rows.filter((r) => r.key !== row.key))}
                    className="rounded-md p-2 text-slate-400 hover:bg-red-50 hover:text-red-600"
                    aria-label={`Remove variant ${row.sku || defaultVariantName(row.attributes) || ''}`}
                  >
                    <TrashIcon />
                  </button>
                )
              )}
            </div>

            {e.row && <p className="text-sm text-red-600 md:col-span-full">{e.row}</p>}
          </div>
        )
      })}
    </div>
  )
}

/** Compact input: label visible on phones, screen-reader-only on desktop (the header row labels it). */
function Cell({ label, error, className, ...rest }: InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string }) {
  const id = useId()
  return (
    <div className="min-w-0">
      <label htmlFor={id} className="mb-1 block text-xs font-medium text-slate-500 md:sr-only">
        {label}
      </label>
      <input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        className={cn(
          'block w-full rounded-lg border bg-white px-2.5 py-2 text-sm text-slate-900 outline-none focus:ring-2 disabled:bg-slate-50 disabled:text-slate-600',
          error ? 'border-red-400 focus:ring-red-100' : 'border-slate-300 focus:border-indigo-500 focus:ring-indigo-100',
          className,
        )}
        {...rest}
      />
      {error && (
        <p id={`${id}-error`} className="mt-1 text-xs text-red-600">
          {error}
        </p>
      )}
    </div>
  )
}
