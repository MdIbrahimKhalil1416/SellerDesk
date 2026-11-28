import { Link, useSearchParams } from 'react-router'
import { TruckIcon } from '../../components/icons'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { Pagination } from '../../components/ui/Pagination'
import { cn } from '../../lib/cn'
import { formatTaka } from '../../lib/money'
import type { ShipmentListParams, ShipmentStatus, ShipmentSummary } from '../../types/shipment'
import { formatDateTime } from '../orders/orderLabels'
import { ShipmentFlags, ShipmentStatusBadge } from './components/ShipmentStatusBadge'
import { useShipmentList } from './queries'
import { COURIER_LABELS, SHIPMENT_STATUS_LABELS, SHIPMENT_STATUSES } from './shipmentLabels'
import { shipmentErrorMessage } from './shipmentErrors'

const PAGE_SIZE = 20

/** What a tab is for, shown above its list. */
const TAB_HINTS: Partial<Record<ShipmentStatus, string>> = {
  BOOKING: 'The courier has not answered yet. SellerDesk retries these automatically.',
  RETURNED: 'Parcels the courier brought back. Open each order and use “Mark returned” to choose what goes back on the shelf.',
  FAILED: 'Bookings the courier refused or never answered. Fix the order if needed and send it again.',
}

/**
 * /shipments (ORDER_VIEW). Status tab and page live in the URL (?status=&page=), newest change
 * first. The "Returned" and "Booking failed" tabs are the work queues (docs/modules/COURIER.md §4).
 */
export function ShipmentListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const statusParam = searchParams.get('status')
  const status = SHIPMENT_STATUSES.find((s) => s === statusParam)
  const params: ShipmentListParams = { status, page: Math.max(0, Number(searchParams.get('page')) || 0), size: PAGE_SIZE }

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

  const list = useShipmentList(params)
  const page = list.data
  const hint = status ? TAB_HINTS[status] : undefined

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">Shipments</h1>
        {page && (
          <p className="text-sm text-slate-500">
            {page.totalElements} shipment{page.totalElements === 1 ? '' : 's'}
          </p>
        )}
      </div>

      <StatusTabs current={status} onChange={(s) => setParams({ status: s })} />
      {hint && <p className="text-sm text-slate-600">{hint}</p>}

      {list.isPending ? (
        <ListSkeleton />
      ) : list.isError ? (
        <Alert tone="error">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span>{shipmentErrorMessage(list.error, 'Could not load shipments.')}</span>
            <Button variant="secondary" onClick={() => void list.refetch()}>
              Try again
            </Button>
          </div>
        </Alert>
      ) : !page ? null : page.content.length === 0 ? (
        <EmptyState
          title={status ? 'No shipments in this tab' : 'No shipments yet'}
          text={status ? `No shipment is “${SHIPMENT_STATUS_LABELS[status]}” right now.` : 'Packed orders you send to a courier appear here.'}
        />
      ) : (
        <div className={list.isFetching ? 'opacity-60 transition-opacity' : undefined} aria-busy={list.isFetching}>
          <ShipmentTable shipments={page.content} />
          <ShipmentCards shipments={page.content} />
          <Pagination page={page.page} totalPages={page.totalPages} loading={list.isFetching} onPage={(p) => setParams({ page: p === 0 ? null : String(p) }, false)} />
        </div>
      )}
    </div>
  )
}

function StatusTabs({ current, onChange }: { current: ShipmentStatus | undefined; onChange: (status: string | null) => void }) {
  const tabs: Array<{ value: ShipmentStatus | null; label: string }> = [
    { value: null, label: 'All' },
    ...SHIPMENT_STATUSES.map((s) => ({ value: s, label: SHIPMENT_STATUS_LABELS[s] })),
  ]
  return (
    <div className="-mx-4 overflow-x-auto px-4 sm:mx-0 sm:px-0" role="tablist" aria-label="Shipment status">
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

function ShipmentTable({ shipments }: { shipments: ShipmentSummary[] }) {
  return (
    <div className="hidden overflow-x-auto rounded-xl border border-slate-200 bg-white md:block">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
          <tr>
            <th className="px-4 py-3 font-medium">Order</th>
            <th className="px-4 py-3 font-medium">Courier</th>
            <th className="px-4 py-3 font-medium">Tracking</th>
            <th className="px-4 py-3 text-right font-medium">COD</th>
            <th className="px-4 py-3 font-medium">Status</th>
            <th className="px-4 py-3 font-medium">Updated</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {shipments.map((s) => (
            <tr key={s.id} className="hover:bg-slate-50">
              <td className="whitespace-nowrap px-4 py-3">
                <Link to={`/orders/${s.orderId}`} className="font-medium tabular-nums text-slate-900 hover:text-indigo-600">
                  {s.orderNumber}
                </Link>
              </td>
              <td className="px-4 py-3 text-slate-700">{COURIER_LABELS[s.provider]}</td>
              <td className="px-4 py-3 font-mono text-xs text-slate-700">{s.trackingCode ?? '—'}</td>
              <td className="whitespace-nowrap px-4 py-3 text-right tabular-nums text-slate-900">
                {formatTaka(s.codAmount)}
                {s.collectedAmount !== null && s.codMismatch && <span className="block text-xs text-amber-700">got {formatTaka(s.collectedAmount)}</span>}
              </td>
              <td className="px-4 py-3">
                <div className="flex flex-wrap items-center gap-1">
                  <ShipmentStatusBadge status={s.status} />
                  <ShipmentFlags codMismatch={s.codMismatch} needsManualCheck={s.needsManualCheck} />
                </div>
                {s.failureReason && <span className="mt-0.5 block text-xs text-red-700">{s.failureReason}</span>}
              </td>
              <td className="whitespace-nowrap px-4 py-3 text-slate-500">{formatDateTime(s.updatedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function ShipmentCards({ shipments }: { shipments: ShipmentSummary[] }) {
  return (
    <ul className="space-y-2 md:hidden">
      {shipments.map((s) => (
        <li key={s.id}>
          <Link to={`/orders/${s.orderId}`} className="block rounded-xl border border-slate-200 bg-white p-3">
            <div className="flex items-start justify-between gap-2">
              <p className="font-medium tabular-nums text-slate-900">{s.orderNumber}</p>
              <ShipmentStatusBadge status={s.status} />
            </div>
            <p className="text-sm text-slate-700">
              {COURIER_LABELS[s.provider]}
              {s.trackingCode && <span className="font-mono text-xs"> · {s.trackingCode}</span>}
            </p>
            <p className="mt-1 text-sm text-slate-500">COD {formatTaka(s.codAmount)}</p>
            {s.failureReason && <p className="text-xs text-red-700">{s.failureReason}</p>}
            <div className="mt-1 flex items-center justify-between gap-2">
              <ShipmentFlags codMismatch={s.codMismatch} needsManualCheck={s.needsManualCheck} />
              <span className="text-xs text-slate-400">{formatDateTime(s.updatedAt)}</span>
            </div>
          </Link>
        </li>
      ))}
    </ul>
  )
}

function EmptyState({ title, text }: { title: string; text: string }) {
  return (
    <div className="flex flex-col items-center rounded-xl border border-dashed border-slate-300 bg-white px-6 py-12 text-center">
      <span className="mb-3 rounded-full bg-indigo-50 p-3 text-indigo-600">
        <TruckIcon />
      </span>
      <h2 className="font-semibold text-slate-900">{title}</h2>
      <p className="mt-1 max-w-sm text-sm text-slate-500">{text}</p>
    </div>
  )
}

function ListSkeleton() {
  return (
    <div className="space-y-2" role="status" aria-label="Loading shipments">
      {Array.from({ length: 5 }, (_, i) => (
        <div key={i} className="animate-pulse space-y-2 rounded-xl border border-slate-200 bg-white p-3">
          <div className="h-3 w-1/4 rounded bg-slate-200" />
          <div className="h-3 w-1/3 rounded bg-slate-100" />
        </div>
      ))}
    </div>
  )
}
