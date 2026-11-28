import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Can } from '../../components/Can'
import { OrdersIcon, PlusIcon, SearchIcon } from '../../components/icons'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { ConfirmDialog } from '../../components/ui/ConfirmDialog'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { TextField } from '../../components/ui/TextField'
import { getApiError } from '../../lib/apiError'
import { cn } from '../../lib/cn'
import { formatTaka } from '../../lib/money'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import type { OrderListParams, OrderSort, OrderStatus, OrderSummary } from '../../types/order'
import type { CourierCode } from '../../types/shipment'
import { usePermissions } from '../auth/usePermissions'
import { SendToCourierDialog } from '../shipments/components/ShipmentDialogs'
import { useBulkSendShipments } from '../shipments/queries'
import { shipmentErrorMessage } from '../shipments/shipmentErrors'
import { CancelDialog, ShipDialog } from './components/ActionDialogs'
import { OrderStatusBadge } from './components/OrderStatusBadge'
import { ACTION_LABELS, ACTION_RULES, BULK_ACTIONS, formatDateTime, SOURCE_LABELS, SOURCE_OPTIONS, STATUS_LABELS, STATUSES, type BulkAction } from './orderLabels'
import { orderErrorMessage } from './orderErrors'
import { useBulkStatus, useOrderList } from './queries'

const PAGE_SIZE = 20

const SORTS: Array<{ value: OrderSort; label: string }> = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
]

const DATE = /^\d{4}-\d{2}-\d{2}$/

/** The answer of a bulk status change or a bulk courier send: one result per order. */
interface BulkOutcome {
  succeeded: number
  results: Array<{ orderId: string; orderNumber?: string | null; ok: boolean; errorCode?: string | null; message?: string | null }>
}

/** What the bulk result says happened to the orders that worked. */
type BulkVerb = 'updated' | 'sent to the courier'

/**
 * /orders. Status tab, filters, search and page live in the URL
 * (?status=&source=&from=&to=&q=&sort=&page=), so refresh and back/forward keep them.
 * Search: order number (SD-1042 or 1042) or the customer's phone in any format.
 */
export function OrderListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()
  const { can } = usePermissions()

  const statusParam = searchParams.get('status')
  const status = STATUSES.find((s) => s === statusParam)
  const params: OrderListParams = {
    status: status ? [status] : undefined,
    source: SOURCE_OPTIONS.find((s) => s.value === searchParams.get('source'))?.value,
    from: DATE.test(searchParams.get('from') ?? '') ? (searchParams.get('from') ?? undefined) : undefined,
    to: DATE.test(searchParams.get('to') ?? '') ? (searchParams.get('to') ?? undefined) : undefined,
    q: searchParams.get('q') ?? undefined,
    sort: SORTS.find((s) => s.value === searchParams.get('sort'))?.value ?? 'createdAt,desc',
    page: Math.max(0, Number(searchParams.get('page')) || 0),
    size: PAGE_SIZE,
  }
  const hasFilters = !!params.source || !!params.from || !!params.to || !!params.q

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

  const list = useOrderList(params)
  const page = list.data

  // ---- Selection for bulk actions: cleared whenever the list shown changes ---------------
  const bulkActions = BULK_ACTIONS.filter((a) => can(ACTION_RULES[a].permission))
  const canSendToCourier = can('ORDER_FULFILL')
  const canSelect = bulkActions.length > 0 || canSendToCourier
  const listKey = searchParams.toString()
  const [selected, setSelected] = useState<Map<string, OrderSummary>>(new Map())
  const [bulkResult, setBulkResult] = useState<{ response: BulkOutcome; numbers: Map<string, string>; verb: BulkVerb } | null>(null)
  const [selectionKey, setSelectionKey] = useState(listKey)
  if (listKey !== selectionKey) {
    setSelectionKey(listKey)
    setSelected(new Map())
  }

  function toggle(order: OrderSummary) {
    setSelected((current) => {
      const next = new Map(current)
      if (next.has(order.id)) next.delete(order.id)
      else next.set(order.id, order)
      return next
    })
  }

  function toggleAll(orders: OrderSummary[]) {
    setSelected((current) => (orders.every((o) => current.has(o.id)) ? new Map() : new Map(orders.map((o) => [o.id, o]))))
  }

  function clearFilters() {
    setSearchText('')
    setSearchParams(new URLSearchParams(status ? { status } : {}), { replace: true })
  }

  const searchError = list.isError && getApiError(list.error)?.fieldErrors.some((f) => f.field === 'q')

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Orders</h1>
          {page && <p className="text-sm text-slate-500">{page.totalElements} order{page.totalElements === 1 ? '' : 's'}</p>}
        </div>
        <Can permission="ORDER_CREATE">
          <Button onClick={() => navigate('/orders/new')}>
            <PlusIcon /> New order
          </Button>
        </Can>
      </div>

      <StatusTabs current={status} onChange={(s) => setParams({ status: s })} />

      <div className="grid gap-3 rounded-xl border border-slate-200 bg-white p-3 sm:grid-cols-2 lg:grid-cols-[2fr_1fr_1fr_1fr_1fr]">
        <div className="relative sm:col-span-2 lg:col-span-1">
          <label htmlFor="order-search" className="sr-only">
            Search orders
          </label>
          <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-slate-400">
            <SearchIcon />
          </span>
          <input
            id="order-search"
            type="search"
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            placeholder="Order number or phone"
            className="block w-full rounded-lg border border-slate-300 bg-white py-2.5 pl-10 pr-3 text-sm outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100"
          />
        </div>
        <Select
          label="Source"
          hideLabel
          value={params.source ?? ''}
          onChange={(e) => setParams({ source: e.target.value })}
          options={[{ value: '', label: 'All sources' }, ...SOURCE_OPTIONS]}
        />
        <DateInput label="From" value={params.from} onChange={(v) => setParams({ from: v })} />
        <DateInput label="To" value={params.to} onChange={(v) => setParams({ to: v })} />
        <Select label="Sort" hideLabel value={params.sort} onChange={(e) => setParams({ sort: e.target.value === 'createdAt,desc' ? null : e.target.value })} options={SORTS} />
      </div>

      {canSelect && selected.size > 0 && (
        <BulkBar
          selected={[...selected.values()]}
          actions={bulkActions}
          canSendToCourier={canSendToCourier}
          onClear={() => setSelected(new Map())}
          onFinished={(response, numbers, verb) => {
            setSelected(new Map())
            setBulkResult({ response, numbers, verb })
          }}
        />
      )}
      {bulkResult && <BulkResult result={bulkResult.response} numbers={bulkResult.numbers} verb={bulkResult.verb} onClose={() => setBulkResult(null)} />}

      {list.isPending ? (
        <ListSkeleton />
      ) : list.isError ? (
        <Alert tone="error">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span>
              {searchError
                ? 'Search by order number (SD-1042 or 1042) or by phone number.'
                : orderErrorMessage(list.error, 'Could not load orders.')}
            </span>
            {!searchError && (
              <Button variant="secondary" onClick={() => void list.refetch()}>
                Try again
              </Button>
            )}
          </div>
        </Alert>
      ) : page && page.content.length === 0 ? (
        hasFilters || status ? (
          <EmptyState title="No orders match" text="Try another search, date range or tab.">
            {hasFilters && (
              <Button variant="secondary" onClick={clearFilters}>
                Clear filters
              </Button>
            )}
          </EmptyState>
        ) : (
          <EmptyState title="No orders yet" text="Orders you take from Facebook, Messenger, WhatsApp or phone appear here.">
            <Can permission="ORDER_CREATE">
              <Button onClick={() => navigate('/orders/new')}>
                <PlusIcon /> New order
              </Button>
            </Can>
          </EmptyState>
        )
      ) : page ? (
        <div className={list.isFetching ? 'opacity-60 transition-opacity' : undefined} aria-busy={list.isFetching}>
          <OrderTable orders={page.content} selectable={canSelect} selected={selected} onToggle={toggle} onToggleAll={toggleAll} />
          <OrderCards orders={page.content} selectable={canSelect} selected={selected} onToggle={toggle} />
          <Pagination page={page.page} totalPages={page.totalPages} loading={list.isFetching} onPage={(p) => setParams({ page: p === 0 ? null : String(p) }, false)} />
        </div>
      ) : null}
    </div>
  )
}

function StatusTabs({ current, onChange }: { current: OrderStatus | undefined; onChange: (status: string | null) => void }) {
  const tabs: Array<{ value: OrderStatus | null; label: string }> = [{ value: null, label: 'All' }, ...STATUSES.map((s) => ({ value: s, label: STATUS_LABELS[s] }))]
  return (
    <div className="-mx-4 overflow-x-auto px-4 sm:mx-0 sm:px-0" role="tablist" aria-label="Order status">
      <div className="flex min-w-max gap-1 border-b border-slate-200">
        {tabs.map((tab) => {
          const active = (tab.value ?? undefined) === current
          return (
            <button
              key={tab.label}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => onChange(tab.value)}
              className={cn(
                '-mb-px border-b-2 px-3 py-2 text-sm font-medium transition',
                active ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-500 hover:text-slate-800',
              )}
            >
              {tab.label}
            </button>
          )
        })}
      </div>
    </div>
  )
}

function DateInput({ label, value, onChange }: { label: string; value: string | undefined; onChange: (value: string | null) => void }) {
  return <TextField label={label} type="date" value={value ?? ''} onChange={(e) => onChange(e.target.value || null)} className="[&_label]:sr-only" aria-label={`${label} date`} />
}

// ==== Bulk actions ==========================================================================

/**
 * The selection bar. Offers only actions the user has the permission for AND that at least one
 * selected order can take now; orders that cannot are reported back by the server.
 * "Send to courier" sends only the selected PACKED orders, in batches of 20.
 */
function BulkBar({ selected, actions, canSendToCourier, onClear, onFinished }: {
  selected: OrderSummary[]
  actions: BulkAction[]
  canSendToCourier: boolean
  onClear: () => void
  /** @param numbers order id → number, for naming failed orders (the server sends ids) */
  onFinished: (response: BulkOutcome, numbers: Map<string, string>, verb: BulkVerb) => void
}) {
  const bulk = useBulkStatus()
  const courier = useBulkSendShipments()
  const [dialog, setDialog] = useState<BulkAction | 'COURIER' | null>(null)
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null)
  const numbers = useMemo(() => new Map(selected.map((o) => [o.id, o.orderNumber])), [selected])
  const packed = selected.filter((o) => o.status === 'PACKED')
  const busy = bulk.isPending || courier.isPending
  /** The actions that only need a yes / no. */
  const simpleDialog = dialog === 'CONFIRM' || dialog === 'PACK' || dialog === 'DELIVER' ? dialog : null

  const possible = actions.filter((a) => selected.some((o) => ACTION_RULES[a].from.includes(o.status)))
  const fitting = (a: BulkAction) => selected.filter((o) => ACTION_RULES[a].from.includes(o.status)).length

  function run(action: BulkAction, extra: { reason?: string; courierName?: string } = {}) {
    bulk.mutate(
      { action, orderIds: selected.map((o) => o.id), ...extra },
      {
        onSuccess: (response) => {
          setDialog(null)
          onFinished(response, numbers, 'updated')
        },
      },
    )
  }

  function sendToCourier(provider: CourierCode) {
    setProgress({ done: 0, total: packed.length })
    courier.mutate(
      { provider, orderIds: packed.map((o) => o.id), onProgress: (done, total) => setProgress({ done, total }) },
      {
        onSuccess: (response) => {
          setDialog(null)
          onFinished(response, numbers, 'sent to the courier')
        },
        onSettled: () => setProgress(null),
      },
    )
  }

  const error = bulk.isError ? orderErrorMessage(bulk.error, 'Could not change the orders.') : null

  return (
    <div className="space-y-2">
      <div className="sticky top-16 z-20 flex flex-wrap items-center gap-2 rounded-xl border border-indigo-200 bg-indigo-50 px-3 py-2">
        <span className="text-sm font-medium text-indigo-900">{selected.length} selected</span>
        <div className="flex flex-1 flex-wrap justify-end gap-2">
          {possible.length === 0 && !(canSendToCourier && packed.length > 0) && (
            <span className="text-sm text-indigo-800">No action fits all of these orders.</span>
          )}
          {canSendToCourier && packed.length > 0 && (
            <Button
              variant="secondary"
              loading={courier.isPending}
              disabled={busy}
              onClick={() => {
                courier.reset()
                setDialog('COURIER')
              }}
            >
              Send to courier ({packed.length})
            </Button>
          )}
          {possible.map((a) => (
            <Button key={a} variant="secondary" loading={bulk.isPending && bulk.variables?.action === a} disabled={busy} onClick={() => setDialog(a)}>
              {ACTION_LABELS[a]} ({fitting(a)})
            </Button>
          ))}
          <Button variant="ghost" onClick={onClear} disabled={busy}>
            Clear
          </Button>
        </div>
      </div>
      {error && !dialog && <Alert tone="error">{error}</Alert>}

      {dialog === 'COURIER' && (
        <SendToCourierDialog
          open
          bulkCount={packed.length}
          skipped={selected.length - packed.length}
          busy={courier.isPending}
          progress={progress}
          error={courier.isError ? shipmentErrorMessage(courier.error, 'Could not send the orders to the courier.') : null}
          onConfirm={(body) => sendToCourier(body.provider)}
          onClose={() => setDialog(null)}
        />
      )}

      {dialog === 'CANCEL' && (
        <CancelDialog
          open
          title={`Cancel ${selected.length} orders?`}
          busy={bulk.isPending}
          error={error}
          onConfirm={(reason) => run('CANCEL', { reason })}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'SHIP' && (
        <ShipDialog
          open
          bulkCount={selected.length}
          busy={bulk.isPending}
          error={error}
          onConfirm={(body) => run('SHIP', { courierName: body.courierName })}
          onClose={() => setDialog(null)}
        />
      )}
      <ConfirmDialog
        open={simpleDialog !== null}
        title={simpleDialog ? `${ACTION_LABELS[simpleDialog]}: ${selected.length} orders?` : ''}
        confirmLabel={simpleDialog ? ACTION_LABELS[simpleDialog] : ''}
        loading={bulk.isPending}
        onConfirm={() => simpleDialog && run(simpleDialog)}
        onCancel={() => setDialog(null)}
      >
        Each order is handled on its own: orders that cannot be changed are listed afterwards, the others go ahead.
        {dialog === 'CONFIRM' && ' Confirming reserves stock.'}
        {error && <span className="mt-2 block text-red-700">{error}</span>}
      </ConfirmDialog>

    </div>
  )
}

function BulkResult({ result, numbers, verb, onClose }: { result: BulkOutcome; numbers: Map<string, string>; verb: BulkVerb; onClose: () => void }) {
  const failed = result.results.filter((r) => !r.ok)
  return (
    <Alert tone={failed.length === 0 ? 'success' : result.succeeded === 0 ? 'error' : 'info'}>
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="font-medium">
            {result.succeeded} {verb}
            {failed.length > 0 && `, ${failed.length} not ${verb === 'updated' ? 'changed' : 'sent'}`}.
          </p>
          {failed.length > 0 && (
            <ul className="mt-1 list-disc pl-5">
              {failed.map((r) => (
                <li key={r.orderId}>
                  <Link to={`/orders/${r.orderId}`} className="font-medium underline">
                    {r.orderNumber ?? numbers.get(r.orderId) ?? 'Order'}
                  </Link>
                  : {r.message ?? r.errorCode}
                </li>
              ))}
            </ul>
          )}
        </div>
        <button type="button" onClick={onClose} className="text-sm font-medium underline">
          Close
        </button>
      </div>
    </Alert>
  )
}

// ==== Rows ==================================================================================

interface RowsProps {
  orders: OrderSummary[]
  selectable: boolean
  selected: Map<string, OrderSummary>
  onToggle: (order: OrderSummary) => void
}

function Checkbox({ checked, onChange, label }: { checked: boolean; onChange: () => void; label: string }) {
  return (
    <input
      type="checkbox"
      checked={checked}
      onChange={onChange}
      aria-label={label}
      className="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
    />
  )
}

function OrderTable({ orders, selectable, selected, onToggle, onToggleAll }: RowsProps & { onToggleAll: (orders: OrderSummary[]) => void }) {
  const allSelected = orders.length > 0 && orders.every((o) => selected.has(o.id))
  return (
    <div className="hidden overflow-x-auto rounded-xl border border-slate-200 bg-white md:block">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
          <tr>
            {selectable && (
              <th className="w-10 px-4 py-3">
                <Checkbox checked={allSelected} onChange={() => onToggleAll(orders)} label="Select all orders on this page" />
              </th>
            )}
            <th className="px-4 py-3 font-medium">Order</th>
            <th className="px-4 py-3 font-medium">Customer</th>
            <th className="px-4 py-3 font-medium">Area</th>
            <th className="px-4 py-3 text-right font-medium">Items</th>
            <th className="px-4 py-3 text-right font-medium">COD</th>
            <th className="px-4 py-3 font-medium">Status</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {orders.map((o) => (
            <tr key={o.id} className={cn('hover:bg-slate-50', selected.has(o.id) && 'bg-indigo-50/50')}>
              {selectable && (
                <td className="px-4 py-3">
                  <Checkbox checked={selected.has(o.id)} onChange={() => onToggle(o)} label={`Select ${o.orderNumber}`} />
                </td>
              )}
              <td className="whitespace-nowrap px-4 py-3">
                <Link to={`/orders/${o.id}`} className="font-medium tabular-nums text-slate-900 hover:text-indigo-600">
                  {o.orderNumber}
                </Link>
                <span className="block text-xs text-slate-500">
                  {formatDateTime(o.createdAt)} · {SOURCE_LABELS[o.source]}
                </span>
              </td>
              <td className="px-4 py-3">
                <span className="block truncate text-slate-900">{o.customerName}</span>
                <span className="text-xs tabular-nums text-slate-500">{o.customerPhone}</span>
              </td>
              <td className="px-4 py-3 text-slate-600">{o.district ?? '—'}</td>
              <td className="px-4 py-3 text-right tabular-nums text-slate-700">{o.itemCount}</td>
              <td className="whitespace-nowrap px-4 py-3 text-right tabular-nums text-slate-900">{formatTaka(o.codAmount)}</td>
              <td className="px-4 py-3">
                <OrderStatusBadge status={o.status} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function OrderCards({ orders, selectable, selected, onToggle }: RowsProps) {
  return (
    <ul className="space-y-2 md:hidden">
      {orders.map((o) => (
        <li key={o.id} className={cn('flex gap-3 rounded-xl border border-slate-200 bg-white p-3', selected.has(o.id) && 'border-indigo-300 bg-indigo-50/50')}>
          {selectable && (
            <div className="pt-0.5">
              <Checkbox checked={selected.has(o.id)} onChange={() => onToggle(o)} label={`Select ${o.orderNumber}`} />
            </div>
          )}
          <Link to={`/orders/${o.id}`} className="min-w-0 flex-1">
            <div className="flex items-start justify-between gap-2">
              <p className="font-medium tabular-nums text-slate-900">{o.orderNumber}</p>
              <OrderStatusBadge status={o.status} />
            </div>
            <p className="truncate text-sm text-slate-700">
              {o.customerName} · <span className="tabular-nums">{o.customerPhone}</span>
            </p>
            <p className="mt-1 text-sm text-slate-500">
              {o.district ?? '—'} · {o.itemCount} item{o.itemCount === 1 ? '' : 's'} · COD {formatTaka(o.codAmount)}
            </p>
            <p className="text-xs text-slate-400">{formatDateTime(o.createdAt)}</p>
          </Link>
        </li>
      ))}
    </ul>
  )
}

function EmptyState({ title, text, children }: { title: string; text: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center rounded-xl border border-dashed border-slate-300 bg-white px-6 py-12 text-center">
      <span className="mb-3 rounded-full bg-indigo-50 p-3 text-indigo-600">
        <OrdersIcon />
      </span>
      <h2 className="font-semibold text-slate-900">{title}</h2>
      <p className="mt-1 max-w-sm text-sm text-slate-500">{text}</p>
      {children && <div className="mt-4">{children}</div>}
    </div>
  )
}

function ListSkeleton() {
  return (
    <div className="space-y-2" role="status" aria-label="Loading orders">
      {Array.from({ length: 5 }, (_, i) => (
        <div key={i} className="animate-pulse space-y-2 rounded-xl border border-slate-200 bg-white p-3">
          <div className="h-3 w-1/4 rounded bg-slate-200" />
          <div className="h-3 w-1/3 rounded bg-slate-100" />
        </div>
      ))}
    </div>
  )
}
