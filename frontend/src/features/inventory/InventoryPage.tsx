import { useEffect, useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router'
import { Can } from '../../components/Can'
import { LayersIcon, SearchIcon } from '../../components/icons'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import type { InventoryListParams, InventorySort, StockRow } from '../../types/inventory'
import { usePermissions } from '../auth/usePermissions'
import { ProductThumb } from '../products/components/ProductThumb'
import { AdjustDialog, RestockDialog } from './components/StockDialogs'
import { InactiveBadge, StockStatusBadge } from './components/StockStatusBadge'
import { inventoryErrorMessage } from './inventoryErrors'
import { STOCK_FILTERS } from './labels'
import { useInventoryList } from './queries'

const PAGE_SIZE = 20

const SORTS: Array<{ value: InventorySort; label: string }> = [
  { value: 'name,asc', label: 'Product name A–Z' },
  { value: 'available,asc', label: 'Lowest available first' },
]

/**
 * /inventory: one row per variant. Filters, sort and page live in the URL
 * (?q=&stockStatus=&inactive=1&sort=&page=), so reload, back and shared links keep them.
 * Restock / Adjust buttons only for STOCK_ADJUST (the backend checks again).
 */
export function InventoryPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { can } = usePermissions()
  const canAdjust = can('STOCK_ADJUST')

  const params: InventoryListParams = {
    q: searchParams.get('q') ?? undefined,
    stockStatus: STOCK_FILTERS.find((f) => f.value === searchParams.get('stockStatus'))?.value ?? 'ALL',
    includeInactive: searchParams.get('inactive') === '1',
    sort: SORTS.find((s) => s.value === searchParams.get('sort'))?.value ?? 'name,asc',
    page: Math.max(0, Number(searchParams.get('page')) || 0),
    size: PAGE_SIZE,
  }
  const hasFilters = !!params.q || params.stockStatus !== 'ALL' || params.includeInactive

  function setParams(changes: Record<string, string | null>, resetPage = true) {
    setSearchParams(
      (current) => {
        const next = new URLSearchParams(current)
        for (const [key, value] of Object.entries(changes)) {
          if (value === null || value === '') next.delete(key)
          else next.set(key, value)
        }
        if (resetPage) next.delete('page')
        return next
      },
      { replace: true },
    )
  }

  // Search box: typing is local; the URL (and the request) follow 300 ms after the last key.
  const [searchText, setSearchText] = useState(params.q ?? '')
  const debouncedSearch = useDebouncedValue(searchText.trim(), 300)
  const urlQuery = params.q ?? ''
  useEffect(() => {
    if (debouncedSearch !== urlQuery) {
      setSearchParams(
        (current) => {
          const next = new URLSearchParams(current)
          if (debouncedSearch) next.set('q', debouncedSearch)
          else next.delete('q')
          next.delete('page')
          return next
        },
        { replace: true },
      )
    }
  }, [debouncedSearch, urlQuery, setSearchParams])

  const list = useInventoryList(params)
  const [restockRow, setRestockRow] = useState<StockRow | null>(null)
  const [adjustRow, setAdjustRow] = useState<StockRow | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  function clearFilters() {
    setSearchText('')
    setSearchParams(new URLSearchParams(params.sort === 'name,asc' ? {} : { sort: params.sort }), { replace: true })
  }

  const actions = canAdjust
    ? {
        onRestock: (row: StockRow) => {
          setSuccess(null)
          setRestockRow(row)
        },
        onAdjust: (row: StockRow) => {
          setSuccess(null)
          setAdjustRow(row)
        },
      }
    : undefined
  const page = list.data

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">Inventory</h1>
        <p className="text-sm text-slate-500">
          Stock per variant. <span className="font-medium text-slate-700">Available</span> = on hand − reserved for confirmed orders.
        </p>
      </div>

      {success && <Alert tone="success">{success}</Alert>}

      {/* Filters */}
      <div className="grid gap-3 rounded-xl border border-slate-200 bg-white p-3 sm:grid-cols-2 lg:grid-cols-[2fr_1fr_1fr_auto] lg:items-center">
        <div className="relative sm:col-span-2 lg:col-span-1">
          <label htmlFor="inventory-search" className="sr-only">
            Search stock
          </label>
          <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-slate-400">
            <SearchIcon />
          </span>
          <input
            id="inventory-search"
            type="search"
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            placeholder="Search by product name or exact SKU"
            className="block w-full rounded-lg border border-slate-300 bg-white py-2.5 pl-10 pr-3 text-sm outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100"
          />
        </div>
        <Select
          label="Stock level"
          hideLabel
          value={params.stockStatus}
          onChange={(e) => setParams({ stockStatus: e.target.value === 'ALL' ? null : e.target.value })}
          options={STOCK_FILTERS}
        />
        <Select
          label="Sort"
          hideLabel
          value={params.sort}
          onChange={(e) => setParams({ sort: e.target.value === 'name,asc' ? null : e.target.value })}
          options={SORTS}
        />
        <label className="flex items-center gap-2 px-1 text-sm text-slate-700">
          <input
            type="checkbox"
            checked={params.includeInactive}
            onChange={(e) => setParams({ inactive: e.target.checked ? '1' : null })}
            className="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
          />
          Show inactive & archived
        </label>
      </div>

      {list.isPending ? (
        <ListSkeleton />
      ) : list.isError ? (
        <Alert tone="error">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span>{inventoryErrorMessage(list.error, 'Could not load the stock list.')}</span>
            <Button variant="secondary" onClick={() => void list.refetch()}>
              Try again
            </Button>
          </div>
        </Alert>
      ) : page && page.content.length === 0 ? (
        hasFilters ? (
          <EmptyState title="Nothing matches" text={params.stockStatus === 'ALL' ? 'Try another search or filter.' : 'Good news: no variant matches this stock level.'}>
            <Button variant="secondary" onClick={clearFilters}>
              Clear filters
            </Button>
          </EmptyState>
        ) : (
          <EmptyState title="No products yet" text="Stock is tracked per variant. Add a product first, then add its opening stock here.">
            <Can permission="PRODUCT_EDIT">
              <Link to="/products/new" className="text-sm font-semibold text-indigo-600 hover:text-indigo-500">
                Add a product
              </Link>
            </Can>
          </EmptyState>
        )
      ) : page ? (
        <div className={list.isFetching ? 'opacity-60 transition-opacity' : undefined} aria-busy={list.isFetching}>
          <p className="mb-2 text-sm text-slate-500">
            {page.totalElements} variant{page.totalElements === 1 ? '' : 's'}
          </p>
          <StockTable rows={page.content} actions={actions} />
          <StockCards rows={page.content} actions={actions} />
          <Pagination
            page={page.page}
            totalPages={page.totalPages}
            loading={list.isFetching}
            onPage={(p) => setParams({ page: p === 0 ? null : String(p) }, false)}
          />
        </div>
      ) : null}

      <RestockDialog row={restockRow} onClose={() => setRestockRow(null)} onDone={setSuccess} />
      <AdjustDialog row={adjustRow} onClose={() => setAdjustRow(null)} onDone={setSuccess} />
    </div>
  )
}

interface RowActions {
  onRestock: (row: StockRow) => void
  onAdjust: (row: StockRow) => void
}

function VariantCell({ row, size }: { row: StockRow; size: number }) {
  return (
    <Link to={`/inventory/variants/${row.variantId}`} className="flex min-w-0 items-center gap-3 hover:text-indigo-600">
      <ProductThumb url={row.coverImageUrl} size={size} />
      <span className="min-w-0">
        <span className="block truncate font-medium text-slate-900">{row.productName}</span>
        <span className="block truncate text-xs text-slate-500">
          {row.variantName} · {row.sku}
        </span>
      </span>
    </Link>
  )
}

function Badges({ row }: { row: StockRow }) {
  return (
    <span className="flex flex-wrap gap-1">
      <StockStatusBadge status={row.stockStatus} />
      {(!row.active || row.productStatus === 'ARCHIVED') && <InactiveBadge archived={row.productStatus === 'ARCHIVED'} />}
    </span>
  )
}

function ThresholdText({ row }: { row: StockRow }) {
  return (
    <span title={row.thresholdSource === 'SHOP' ? 'Shop default (Settings)' : 'Set on this variant'}>
      ≤ {row.lowStockThreshold}
      {row.thresholdSource === 'SHOP' && <span className="block text-xs text-slate-400">shop default</span>}
    </span>
  )
}

/** Desktop. */
function StockTable({ rows, actions }: { rows: StockRow[]; actions?: RowActions }) {
  return (
    <div className="hidden overflow-x-auto rounded-xl border border-slate-200 bg-white md:block">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
          <tr>
            <th className="px-4 py-3 font-medium">Variant</th>
            <th className="px-4 py-3 text-right font-medium">Available</th>
            <th className="px-4 py-3 text-right font-medium">On hand</th>
            <th className="px-4 py-3 text-right font-medium">Reserved</th>
            <th className="px-4 py-3 text-right font-medium">Low at</th>
            <th className="px-4 py-3 font-medium">Status</th>
            {actions && <th className="px-4 py-3 text-right font-medium">Actions</th>}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {rows.map((row) => (
            <tr key={row.variantId} className="hover:bg-slate-50">
              <td className="max-w-xs px-4 py-3">
                <VariantCell row={row} size={40} />
              </td>
              <td className="px-4 py-3 text-right text-base font-semibold tabular-nums text-slate-900">{row.available}</td>
              <td className="px-4 py-3 text-right tabular-nums text-slate-600">{row.onHand}</td>
              <td className="px-4 py-3 text-right tabular-nums text-slate-600">{row.reserved}</td>
              <td className="px-4 py-3 text-right tabular-nums text-slate-600">
                <ThresholdText row={row} />
              </td>
              <td className="px-4 py-3">
                <Badges row={row} />
              </td>
              {actions && (
                <td className="whitespace-nowrap px-4 py-3 text-right">
                  <Button variant="ghost" className="px-2 py-1.5" onClick={() => actions.onRestock(row)}>
                    Restock
                  </Button>
                  <Button variant="ghost" className="px-2 py-1.5" onClick={() => actions.onAdjust(row)}>
                    Adjust
                  </Button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Phones: one card per variant. */
function StockCards({ rows, actions }: { rows: StockRow[]; actions?: RowActions }) {
  return (
    <ul className="space-y-2 md:hidden">
      {rows.map((row) => (
        <li key={row.variantId} className="rounded-xl border border-slate-200 bg-white p-3">
          <div className="flex items-start justify-between gap-2">
            <VariantCell row={row} size={48} />
            <Badges row={row} />
          </div>
          <dl className="mt-3 grid grid-cols-3 gap-2 text-center">
            <Stat label="Available" value={row.available} strong />
            <Stat label="On hand" value={row.onHand} />
            <Stat label="Reserved" value={row.reserved} />
          </dl>
          {actions && (
            <div className="mt-3 grid grid-cols-2 gap-2">
              <Button variant="secondary" onClick={() => actions.onRestock(row)}>
                Restock
              </Button>
              <Button variant="secondary" onClick={() => actions.onAdjust(row)}>
                Adjust
              </Button>
            </div>
          )}
        </li>
      ))}
    </ul>
  )
}

function Stat({ label, value, strong }: { label: string; value: number; strong?: boolean }) {
  return (
    <div className="rounded-lg bg-slate-50 px-2 py-1.5">
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className={strong ? 'text-lg font-semibold tabular-nums text-slate-900' : 'tabular-nums text-slate-700'}>{value}</dd>
    </div>
  )
}

function EmptyState({ title, text, children }: { title: string; text: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center rounded-xl border border-dashed border-slate-300 bg-white px-6 py-12 text-center">
      <span className="mb-3 rounded-full bg-indigo-50 p-3 text-indigo-600">
        <LayersIcon />
      </span>
      <h2 className="font-semibold text-slate-900">{title}</h2>
      <p className="mt-1 max-w-sm text-sm text-slate-500">{text}</p>
      {children && <div className="mt-4">{children}</div>}
    </div>
  )
}

function ListSkeleton() {
  return (
    <div className="space-y-2" role="status" aria-label="Loading stock">
      {Array.from({ length: 5 }, (_, i) => (
        <div key={i} className="flex animate-pulse items-center gap-3 rounded-xl border border-slate-200 bg-white p-3">
          <div className="h-10 w-10 rounded-lg bg-slate-200" />
          <div className="flex-1 space-y-2">
            <div className="h-3 w-1/3 rounded bg-slate-200" />
            <div className="h-3 w-1/5 rounded bg-slate-100" />
          </div>
          <div className="h-6 w-12 rounded bg-slate-200" />
        </div>
      ))}
    </div>
  )
}
