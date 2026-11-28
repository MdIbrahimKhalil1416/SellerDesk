import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ArrowLeftIcon } from '../../components/icons'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { ConfirmDialog } from '../../components/ui/ConfirmDialog'
import { Spinner } from '../../components/ui/Spinner'
import { getApiError } from '../../lib/apiError'
import { cn } from '../../lib/cn'
import { formatTaka } from '../../lib/money'
import type { Order, OrderAction } from '../../types/order'
import { usePermissions } from '../auth/usePermissions'
import { ZONE_LABELS } from '../customers/customerForm'
import { CancelDialog, ReturnDialog, ShipDialog } from './components/ActionDialogs'
import { MoneyLines } from './components/MoneyLines'
import { OrderStatusBadge } from './components/OrderStatusBadge'
import { OrderTimeline } from './components/OrderTimeline'
import { ACTION_LABELS, formatDateTime, SOURCE_LABELS } from './orderLabels'
import { errorCodeOf, orderErrorMessage, shortItemsOf } from './orderErrors'
import { useOrder, useOrderAction, type OrderActionInput } from './queries'

/** /orders/:id (ORDER_VIEW). */
export function OrderDetailPage() {
  const { id = '' } = useParams()
  const order = useOrder(id)

  if (order.isPending) {
    return (
      <div className="flex items-center gap-3 py-12 text-slate-600" role="status">
        <Spinner /> Loading order…
      </div>
    )
  }
  if (order.isError) {
    const notFound = getApiError(order.error)?.code === 'NOT_FOUND'
    return (
      <div className="mx-auto max-w-md rounded-xl border border-slate-200 bg-white p-6 text-center">
        <h1 className="text-lg font-semibold text-slate-900">{notFound ? 'Order not found' : 'Could not load the order'}</h1>
        <p className="mt-2 text-sm text-slate-600">{notFound ? 'The link may be wrong.' : orderErrorMessage(order.error)}</p>
        <div className="mt-4 flex justify-center gap-2">
          <Link to="/orders" className="text-sm font-semibold text-indigo-600 hover:text-indigo-500">
            Back to orders
          </Link>
          {!notFound && (
            <Button variant="secondary" onClick={() => void order.refetch()}>
              Try again
            </Button>
          )}
        </div>
      </div>
    )
  }
  return <OrderDetail order={order.data} />
}

/** Which dialog is open. CONFIRM and PACK need none. */
type OpenDialog = 'SHIP' | 'DELIVER' | 'RETURN' | 'CANCEL' | null

function OrderDetail({ order }: { order: Order }) {
  const { can } = usePermissions()
  const action = useOrderAction(order.id)
  const [dialog, setDialog] = useState<OpenDialog>(null)
  const [message, setMessage] = useState<{ tone: 'success' | 'error'; text: string } | null>(null)
  /** After a failed confirm: item index → "SKU: requested 3, available 1". */
  const [shortItems, setShortItems] = useState<Map<number, string>>(new Map())

  function run(input: OrderActionInput, done: string) {
    setMessage(null)
    setShortItems(new Map())
    action.mutate(input, {
      onSuccess: () => {
        setDialog(null)
        setMessage({ tone: 'success', text: done })
      },
      onError: (e) => {
        if (errorCodeOf(e) === 'INSUFFICIENT_STOCK') {
          setShortItems(shortItemsOf(e))
          setMessage({ tone: 'error', text: getApiError(e)?.message ?? 'Not enough stock to confirm this order.' })
        } else if (dialog === null) {
          setMessage({ tone: 'error', text: orderErrorMessage(e, 'Could not change the status.') })
        }
        // Errors of an open dialog are shown inside it (dialogError below).
      },
    })
  }

  function onAction(a: OrderAction) {
    if (a === 'CONFIRM') run({ action: 'CONFIRM' }, 'Order confirmed. Stock is reserved.')
    else if (a === 'PACK') run({ action: 'PACK' }, 'Order marked packed.')
    else {
      action.reset()
      setDialog(a)
    }
  }

  const dialogError = dialog && action.isError ? orderErrorMessage(action.error, 'Could not change the status.') : null
  const stockReserved = order.status === 'CONFIRMED' || order.status === 'PACKED'
  const busyAction = action.isPending ? action.variables?.action : undefined

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <Link to="/orders" className="inline-flex items-center gap-1 text-sm font-medium text-slate-600 hover:text-slate-900">
        <ArrowLeftIcon /> Orders
      </Link>

      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-semibold tabular-nums text-slate-900">{order.orderNumber}</h1>
            <OrderStatusBadge status={order.status} />
          </div>
          <p className="mt-1 text-sm text-slate-500">
            {formatDateTime(order.createdAt)} · {SOURCE_LABELS[order.source]} · by {order.createdBy.name}
          </p>
        </div>
        {order.allowedActions.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {order.allowedActions.map((a) => (
              <Button
                key={a}
                variant={a === 'CANCEL' ? 'secondary' : 'primary'}
                className={a === 'CANCEL' ? 'text-red-700' : undefined}
                loading={busyAction === a}
                disabled={action.isPending && busyAction !== a}
                onClick={() => onAction(a)}
              >
                {ACTION_LABELS[a]}
              </Button>
            ))}
          </div>
        )}
      </div>

      {message && (
        <Alert tone={message.tone}>
          {message.text}
          {shortItems.size > 0 && (
            <ul className="mt-1 list-disc pl-5">
              {[...shortItems.values()].map((text) => (
                <li key={text}>{text}</li>
              ))}
            </ul>
          )}
          {shortItems.size > 0 && can('STOCK_ADJUST') && (
            <p className="mt-1">
              <Link to="/inventory?stockStatus=LOW_OR_OUT" className="font-semibold underline">
                Restock in Inventory
              </Link>
            </p>
          )}
        </Alert>
      )}
      {order.status === 'CANCELLED' && order.cancelReason && <Alert tone="info">Cancelled: {order.cancelReason}</Alert>}
      {order.status === 'RETURNED' && order.returnReason && <Alert tone="info">Returned: {order.returnReason}</Alert>}

      <div className="grid gap-6 lg:grid-cols-[2fr_1fr]">
        <div className="space-y-6">
          <ItemsSection order={order} shortItems={shortItems} />
          <OrderTimeline orderId={order.id} />
        </div>
        <div className="space-y-6">
          <CustomerSection order={order} linkToCustomer={can('CUSTOMER_VIEW')} />
          <MoneySection order={order} />
          {(order.shipping || order.note) && (
            <section className="space-y-2 rounded-xl border border-slate-200 bg-white p-4 text-sm sm:p-5">
              {order.shipping && (
                <p className="text-slate-700">
                  <span className="font-medium text-slate-900">Courier: </span>
                  {[order.shipping.courierName, order.shipping.trackingId].filter(Boolean).join(' · ') || '—'}
                </p>
              )}
              {order.note && (
                <p className="text-slate-700">
                  <span className="font-medium text-slate-900">Note: </span>
                  {order.note}
                </p>
              )}
            </section>
          )}
        </div>
      </div>

      {dialog === 'CANCEL' && (
        <CancelDialog
          open
          title={`Cancel ${order.orderNumber}?`}
          stockNote={stockReserved}
          busy={action.isPending}
          error={dialogError}
          onConfirm={(reason) => run({ action: 'CANCEL', reason }, 'Order cancelled.')}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'SHIP' && (
        <ShipDialog
          open
          busy={action.isPending}
          error={dialogError}
          onConfirm={(body) => run({ action: 'SHIP', body }, 'Order marked shipped.')}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'RETURN' && (
        <ReturnDialog
          open
          items={order.items}
          busy={action.isPending}
          error={dialogError}
          onConfirm={(body) => run({ action: 'RETURN', body }, 'Order marked returned.')}
          onClose={() => setDialog(null)}
        />
      )}
      <ConfirmDialog
        open={dialog === 'DELIVER'}
        title="Mark delivered?"
        confirmLabel="Mark delivered"
        loading={action.isPending}
        onConfirm={() => run({ action: 'DELIVER' }, 'Order marked delivered.')}
        onCancel={() => setDialog(null)}
      >
        The courier delivered the parcel and collected {formatTaka(order.codAmount)}. This counts the order on the customer.
        {dialogError && <span className="mt-2 block text-red-700">{dialogError}</span>}
      </ConfirmDialog>
    </div>
  )
}

function ItemsSection({ order, shortItems }: { order: Order; shortItems: Map<number, string> }) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white">
      <h2 className="border-b border-slate-200 px-4 py-3 font-semibold text-slate-900 sm:px-5">Items</h2>
      <ul className="divide-y divide-slate-100">
        {order.items.map((item, i) => {
          const short = shortItems.get(i)
          const lowNow = item.available !== undefined && item.available < item.quantity
          return (
            <li key={item.variantId} className={cn('flex gap-3 px-4 py-3 sm:px-5', short && 'bg-red-50')}>
              <div className="min-w-0 flex-1">
                <p className="truncate font-medium text-slate-900">{item.productName}</p>
                <p className="text-sm text-slate-500">
                  {item.variantName} · <span className="font-mono text-xs">{item.sku}</span>
                </p>
                {short && <p className="text-sm text-red-700">{short}</p>}
                {!short && lowNow && (
                  <p className="text-sm text-amber-700">Only {item.available} available now: confirming will fail until you restock.</p>
                )}
                {!short && !lowNow && item.available !== undefined && <p className="text-xs text-slate-500">{item.available} available</p>}
                {item.returnedRestocked !== null && (
                  <p className="text-xs text-slate-500">{item.returnedRestocked ? 'Returned to stock' : 'Not restocked (damaged)'}</p>
                )}
              </div>
              <div className="text-right text-sm tabular-nums">
                <p className="text-slate-600">
                  {item.quantity} × {formatTaka(item.unitPrice)}
                </p>
                <p className="font-medium text-slate-900">{formatTaka(item.lineTotal)}</p>
              </div>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function CustomerSection({ order, linkToCustomer }: { order: Order; linkToCustomer: boolean }) {
  const a = order.customer.address
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4 text-sm sm:p-5">
      <h2 className="font-semibold text-slate-900">Customer</h2>
      <p className="mt-2 font-medium text-slate-900">
        {linkToCustomer ? (
          <Link to={`/customers/${order.customer.id}`} className="hover:text-indigo-600">
            {order.customer.name}
          </Link>
        ) : (
          order.customer.name
        )}
      </p>
      <a href={`tel:${order.customer.phone}`} className="tabular-nums text-slate-700 hover:text-indigo-600">
        {order.customer.phone}
      </a>
      <p className="mt-2 text-slate-700">{a.line}</p>
      <p className="text-slate-700">{[a.area, a.district].filter(Boolean).join(', ')}</p>
      {a.note && <p className="text-slate-500">{a.note}</p>}
      <p className="mt-1 text-xs text-slate-500">{ZONE_LABELS[a.zone]}</p>
    </section>
  )
}

function MoneySection({ order }: { order: Order }) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4 text-sm sm:p-5">
      <h2 className="font-semibold text-slate-900">Payment</h2>
      <MoneyLines
        subtotal={order.subtotal}
        discount={order.discount}
        deliveryCharge={order.deliveryCharge}
        total={order.total}
        advancePaid={order.advancePaid}
        codAmount={order.codAmount}
      />
    </section>
  )
}
