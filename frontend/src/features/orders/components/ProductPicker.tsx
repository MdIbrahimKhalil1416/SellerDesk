import { useMemo, useState } from 'react'
import { SearchIcon } from '../../../components/icons'
import { Alert } from '../../../components/ui/Alert'
import { Button } from '../../../components/ui/Button'
import { Spinner } from '../../../components/ui/Spinner'
import { cn } from '../../../lib/cn'
import { formatTaka } from '../../../lib/money'
import { useDebouncedValue } from '../../../lib/useDebouncedValue'
import { usePermissions } from '../../auth/usePermissions'
import { orderErrorMessage } from '../orderErrors'
import { usePickerStock, useProductLookup } from '../queries'

/** What the form needs from a picked variant. Price in POISHA. */
export interface PickedVariant {
  variantId: string
  sku: string
  productName: string
  variantName: string
  price: number
  /** Stock when it was picked; undefined without STOCK_VIEW. */
  available?: number
}

const MAX_RESULTS = 20

/**
 * Search the shop's active products (name, variant name or SKU) and add a variant to the order.
 * With STOCK_VIEW every row shows the live `available`; a sold-out variant can still be added
 * (the order is only checked against stock on confirm), but it is marked clearly.
 */
export function ProductPicker({ addedIds, onAdd }: { addedIds: Set<string>; onAdd: (variant: PickedVariant) => void }) {
  const [text, setText] = useState('')
  const lookup = useProductLookup()
  const stock = useStockMap(text)
  const term = text.trim().toLowerCase()

  const rows = useMemo(() => {
    const result: PickedVariant[] = []
    for (const product of lookup.data ?? []) {
      for (const v of product.variants) {
        const matches =
          !term ||
          product.name.toLowerCase().includes(term) ||
          v.name.toLowerCase().includes(term) ||
          v.sku.toLowerCase().startsWith(term)
        if (matches) result.push({ variantId: v.variantId, sku: v.sku, productName: product.name, variantName: v.name, price: v.price })
      }
    }
    return result
  }, [lookup.data, term])

  return (
    <div className="space-y-2">
      <div className="relative">
        <label htmlFor="product-search" className="sr-only">
          Search products
        </label>
        <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-slate-400">
          <SearchIcon />
        </span>
        <input
          id="product-search"
          type="search"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Search product, size/colour or SKU"
          className="block w-full rounded-lg border border-slate-300 bg-white py-2.5 pl-10 pr-3 text-sm outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100"
        />
      </div>

      {lookup.isPending ? (
        <p className="flex items-center gap-2 py-3 text-sm text-slate-500" role="status">
          <Spinner /> Loading products…
        </p>
      ) : lookup.isError ? (
        <Alert tone="error">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span>{orderErrorMessage(lookup.error, 'Could not load products.')}</span>
            <Button variant="secondary" onClick={() => void lookup.refetch()}>
              Try again
            </Button>
          </div>
        </Alert>
      ) : rows.length === 0 ? (
        <p className="rounded-lg border border-dashed border-slate-300 px-3 py-4 text-center text-sm text-slate-500">
          {lookup.data.length === 0 ? 'No active products yet. Add products first.' : 'No product matches.'}
        </p>
      ) : (
        <ul className="max-h-72 divide-y divide-slate-100 overflow-y-auto rounded-lg border border-slate-200">
          {rows.slice(0, MAX_RESULTS).map((row) => {
            const available = stock.get(row.variantId)
            const added = addedIds.has(row.variantId)
            return (
              <li key={row.variantId} className="flex items-center gap-3 px-3 py-2">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-slate-900">{row.productName}</p>
                  <p className="text-xs text-slate-500">
                    {row.variantName} · <span className="font-mono">{row.sku}</span> · {formatTaka(row.price)}
                  </p>
                </div>
                {available !== undefined && (
                  <span
                    className={cn(
                      'whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium',
                      available <= 0 ? 'bg-red-50 text-red-700' : available <= 5 ? 'bg-amber-50 text-amber-800' : 'bg-emerald-50 text-emerald-700',
                    )}
                  >
                    {available <= 0 ? 'Out of stock' : `${available} available`}
                  </span>
                )}
                <Button variant="secondary" disabled={added} onClick={() => onAdd({ ...row, available })} className="px-3 py-1.5">
                  {added ? 'Added' : 'Add'}
                </Button>
              </li>
            )
          })}
          {rows.length > MAX_RESULTS && <li className="px-3 py-2 text-xs text-slate-500">{rows.length - MAX_RESULTS} more: type more to narrow the list.</li>}
        </ul>
      )}
    </div>
  )
}

/**
 * Live `available` per variant for users with STOCK_VIEW (the stock list, searched with the same
 * text). Empty for everyone else.
 */
function useStockMap(text: string): Map<string, number> {
  const { can } = usePermissions()
  const debounced = useDebouncedValue(text.trim(), 300)
  const stock = usePickerStock(debounced, can('STOCK_VIEW'))
  return useMemo(() => new Map((stock.data?.content ?? []).map((row) => [row.variantId, row.available])), [stock.data])
}
