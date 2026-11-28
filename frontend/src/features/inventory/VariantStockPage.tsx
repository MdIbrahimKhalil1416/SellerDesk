import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { ArrowLeftIcon } from '../../components/icons'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { Pagination } from '../../components/ui/Pagination'
import { Spinner } from '../../components/ui/Spinner'
import { getApiError } from '../../lib/apiError'
import type { StockRow } from '../../types/inventory'
import { usePermissions } from '../auth/usePermissions'
import { ProductThumb } from '../products/components/ProductThumb'
import { MovementList } from './components/MovementList'
import { AdjustDialog, RestockDialog } from './components/StockDialogs'
import { InactiveBadge, StockStatusBadge } from './components/StockStatusBadge'
import { inventoryErrorMessage } from './inventoryErrors'
import { useMovements, useVariantStock } from './queries'

const HISTORY_PAGE_SIZE = 20

/** /inventory/variants/:variantId: stock numbers, threshold, actions and the full movement history. */
export function VariantStockPage() {
  const { variantId = '' } = useParams()
  const detail = useVariantStock(variantId)

  if (detail.isPending) {
    return (
      <div className="flex items-center gap-3 py-12 text-slate-600" role="status">
        <Spinner /> Loading stock…
      </div>
    )
  }
  if (detail.isError) {
    const notFound = getApiError(detail.error)?.code === 'NOT_FOUND'
    return (
      <div className="mx-auto max-w-md rounded-xl border border-slate-200 bg-white p-6 text-center">
        <h1 className="text-lg font-semibold text-slate-900">{notFound ? 'Variant not found' : 'Could not load the stock'}</h1>
        <p className="mt-2 text-sm text-slate-600">
          {notFound ? 'It may have been deleted, or the link is wrong.' : inventoryErrorMessage(detail.error)}
        </p>
        <div className="mt-4 flex justify-center gap-2">
          <Link to="/inventory" className="text-sm font-semibold text-indigo-600 hover:text-indigo-500">
            Back to inventory
          </Link>
          {!notFound && (
            <Button variant="secondary" onClick={() => void detail.refetch()}>
              Try again
            </Button>
          )}
        </div>
      </div>
    )
  }
  return <VariantStock key={variantId} row={detail.data.row} />
}

function VariantStock({ row }: { row: StockRow }) {
  const { can } = usePermissions()
  const [searchParams, setSearchParams] = useSearchParams()
  const page = Math.max(0, Number(searchParams.get('page')) || 0)
  const history = useMovements({ variantId: row.variantId, page, size: HISTORY_PAGE_SIZE })
  const [dialog, setDialog] = useState<'restock' | 'adjust' | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  function open(which: 'restock' | 'adjust') {
    setSuccess(null)
    setDialog(which)
  }

  return (
    <div className="space-y-6">
      <Link to="/inventory" className="inline-flex items-center gap-1 text-sm font-medium text-slate-600 hover:text-slate-900">
        <ArrowLeftIcon /> Inventory
      </Link>

      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 items-center gap-3">
          <ProductThumb url={row.coverImageUrl} size={56} />
          <div className="min-w-0">
            <h1 className="truncate text-xl font-semibold text-slate-900">{row.productName}</h1>
            <p className="text-sm text-slate-500">
              {row.variantName} · SKU {row.sku}
            </p>
            <div className="mt-1 flex flex-wrap gap-1">
              <StockStatusBadge status={row.stockStatus} />
              {(!row.active || row.productStatus === 'ARCHIVED') && <InactiveBadge archived={row.productStatus === 'ARCHIVED'} />}
            </div>
          </div>
        </div>
        {can('STOCK_ADJUST') && (
          <div className="flex gap-2">
            <Button onClick={() => open('restock')}>Restock</Button>
            <Button variant="secondary" onClick={() => open('adjust')}>
              Adjust
            </Button>
          </div>
        )}
      </div>

      {success && <Alert tone="success">{success}</Alert>}

      <dl className="grid grid-cols-3 gap-3">
        <Tile label="Available" value={row.available} hint="Can still be sold" strong />
        <Tile label="On hand" value={row.onHand} hint="On the shelf" />
        <Tile label="Reserved" value={row.reserved} hint="For confirmed orders" />
      </dl>

      <p className="rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm text-slate-600">
        Shown as <span className="font-medium text-amber-800">low stock</span> when 1 to {row.lowStockThreshold} are available
        {row.thresholdSource === 'SHOP' ? (
          <>
            {' '}
            (the shop default
            {can('SHOP_SETTINGS') && (
              <>
                , changed in{' '}
                <Link to="/settings" className="font-medium text-indigo-600 hover:text-indigo-500">
                  Settings
                </Link>
              </>
            )}
            ).
          </>
        ) : (
          <>
            {' '}
            (set on this variant
            {can('PRODUCT_EDIT') && (
              <>
                , changed on the{' '}
                <Link to={`/products/${row.productId}`} className="font-medium text-indigo-600 hover:text-indigo-500">
                  product page
                </Link>
              </>
            )}
            ).
          </>
        )}
      </p>

      <section>
        <h2 className="mb-3 font-semibold text-slate-900">Movement history</h2>
        {history.isPending ? (
          <div className="flex items-center gap-3 py-6 text-sm text-slate-600" role="status">
            <Spinner /> Loading history…
          </div>
        ) : history.isError ? (
          <Alert tone="error">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span>{inventoryErrorMessage(history.error, 'Could not load the history.')}</span>
              <Button variant="secondary" onClick={() => void history.refetch()}>
                Try again
              </Button>
            </div>
          </Alert>
        ) : history.data.content.length === 0 ? (
          <p className="rounded-xl border border-dashed border-slate-300 bg-white px-4 py-8 text-center text-sm text-slate-500">
            No stock changes yet.{can('STOCK_ADJUST') ? ' Use Restock to add the opening stock.' : ''}
          </p>
        ) : (
          <div className={history.isFetching ? 'opacity-60 transition-opacity' : undefined} aria-busy={history.isFetching}>
            <MovementList movements={history.data.content} />
            <Pagination
              page={history.data.page}
              totalPages={history.data.totalPages}
              loading={history.isFetching}
              onPage={(p) => setSearchParams(p === 0 ? {} : { page: String(p) }, { replace: true })}
            />
          </div>
        )}
      </section>

      <RestockDialog row={dialog === 'restock' ? row : null} onClose={() => setDialog(null)} onDone={setSuccess} />
      <AdjustDialog row={dialog === 'adjust' ? row : null} onClose={() => setDialog(null)} onDone={setSuccess} />
    </div>
  )
}

function Tile({ label, value, hint, strong }: { label: string; value: number; hint: string; strong?: boolean }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-3 sm:p-4">
      <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className={strong ? 'mt-1 text-2xl font-semibold tabular-nums text-slate-900' : 'mt-1 text-xl font-semibold tabular-nums text-slate-700'}>
        {value}
      </dd>
      <p className="mt-0.5 hidden text-xs text-slate-400 sm:block">{hint}</p>
    </div>
  )
}
