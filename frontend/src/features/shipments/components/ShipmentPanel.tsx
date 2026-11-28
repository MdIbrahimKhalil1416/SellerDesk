import { useState, type ReactNode } from 'react'
import { Alert } from '../../../components/ui/Alert'
import { Button } from '../../../components/ui/Button'
import { Spinner } from '../../../components/ui/Spinner'
import { formatTaka } from '../../../lib/money'
import type { Order } from '../../../types/order'
import type { Shipment, ShipmentEvent, ShipmentSummary } from '../../../types/shipment'
import { formatDateTime } from '../../orders/orderLabels'
import { useCancelShipment, useOrderShipments, useShipment, useTrackShipment } from '../queries'
import { COURIER_LABELS, EVENT_SOURCE_LABELS, SHIPMENT_STATUS_LABELS } from '../shipmentLabels'
import { shipmentErrorMessage } from '../shipmentErrors'
import { CancelShipmentDialog } from './ShipmentDialogs'
import { ShipmentFlags, ShipmentStatusBadge } from './ShipmentStatusBadge'

/**
 * The courier card on the order page: the latest booking with its tracking code, events and the
 * actions the server allows (TRACK, CANCEL), plus earlier attempts. Hidden for orders that were
 * never sent and can no longer be (not PACKED): those were shipped by hand or never got that far.
 */
export function ShipmentPanel({ order }: { order: Order }) {
  const shipments = useOrderShipments(order.id)

  if (shipments.isPending) {
    return (
      <Section>
        <p className="mt-3 flex items-center gap-2 text-sm text-slate-500" role="status">
          <Spinner /> Loading…
        </p>
      </Section>
    )
  }
  if (shipments.isError) {
    return (
      <Section>
        <Alert tone="error" className="mt-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span>{shipmentErrorMessage(shipments.error, 'Could not load the courier bookings.')}</span>
            <Button variant="secondary" onClick={() => void shipments.refetch()}>
              Try again
            </Button>
          </div>
        </Alert>
      </Section>
    )
  }

  const [latest, ...earlier] = shipments.data
  if (!latest) {
    if (order.status !== 'PACKED') return null
    return (
      <Section>
        <p className="mt-2 text-sm text-slate-500">Not sent to a courier yet.</p>
      </Section>
    )
  }
  return (
    <Section>
      <LatestShipment summary={latest} order={order} />
      {earlier.length > 0 && <EarlierAttempts shipments={earlier} />}
    </Section>
  )
}

function Section({ children }: { children: ReactNode }) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
      <h2 className="font-semibold text-slate-900">Courier</h2>
      {children}
    </section>
  )
}

/** The list row is shown at once; the full shipment (events, actions) loads behind it. */
function LatestShipment({ summary, order }: { summary: ShipmentSummary; order: Order }) {
  const detail = useShipment(summary.id)
  const track = useTrackShipment(summary.id)
  const cancel = useCancelShipment(summary.id)
  const [cancelOpen, setCancelOpen] = useState(false)
  const [message, setMessage] = useState<{ tone: 'success' | 'error'; text: string } | null>(null)

  const shipment: Shipment | undefined = detail.data
  const s = shipment ?? summary
  const actions = shipment?.allowedActions ?? []

  function onTrack() {
    setMessage(null)
    track.mutate(undefined, {
      onSuccess: (updated) => setMessage({ tone: 'success', text: `Courier status: ${SHIPMENT_STATUS_LABELS[updated.status]}.` }),
      onError: (e) => setMessage({ tone: 'error', text: shipmentErrorMessage(e, 'Could not ask the courier.') }),
    })
  }

  function onCancel(reason: string) {
    setMessage(null)
    cancel.mutate(reason, {
      onSuccess: () => {
        setCancelOpen(false)
        setMessage({ tone: 'success', text: 'Booking cancelled. The order is still packed and can be sent again.' })
      },
    })
  }

  return (
    <div className="mt-3 space-y-3 text-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <ShipmentStatusBadge status={s.status} />
          <span className="text-slate-700">{COURIER_LABELS[s.provider]}</span>
          <ShipmentFlags codMismatch={s.codMismatch} needsManualCheck={s.needsManualCheck} />
        </div>
        {actions.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {actions.includes('TRACK') && (
              <Button variant="secondary" loading={track.isPending} disabled={cancel.isPending} onClick={onTrack}>
                Refresh status
              </Button>
            )}
            {actions.includes('CANCEL') && (
              <Button
                variant="secondary"
                className="text-red-700"
                disabled={track.isPending}
                onClick={() => {
                  cancel.reset()
                  setCancelOpen(true)
                }}
              >
                Cancel booking
              </Button>
            )}
          </div>
        )}
      </div>

      {message && <Alert tone={message.tone}>{message.text}</Alert>}
      <StatusNote shipment={s} cancelReason={shipment?.cancelReason ?? null} orderStatus={order.status} />

      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
        <dt className="text-slate-500">Tracking code</dt>
        <dd className="font-mono text-slate-900">{s.trackingCode ?? '—'}</dd>
        <dt className="text-slate-500">Consignment</dt>
        <dd className="font-mono text-xs leading-5 text-slate-700">{s.consignmentId ?? '—'}</dd>
        <dt className="text-slate-500">COD</dt>
        <dd className="tabular-nums text-slate-900">{formatTaka(s.codAmount)}</dd>
        {s.collectedAmount !== null && (
          <>
            <dt className="text-slate-500">Collected</dt>
            <dd className={s.codMismatch ? 'tabular-nums font-medium text-amber-700' : 'tabular-nums text-slate-900'}>{formatTaka(s.collectedAmount)}</dd>
          </>
        )}
        {shipment?.courierFee != null && (
          <>
            <dt className="text-slate-500">Courier fee</dt>
            <dd className="tabular-nums text-slate-900">{formatTaka(shipment.courierFee)}</dd>
          </>
        )}
        {shipment?.note && (
          <>
            <dt className="text-slate-500">Rider note</dt>
            <dd className="text-slate-700">{shipment.note}</dd>
          </>
        )}
        <dt className="text-slate-500">Sent</dt>
        <dd className="text-slate-700">
          {formatDateTime(s.createdAt)}
          {shipment && ` · by ${shipment.createdBy.name}`}
        </dd>
      </dl>

      {detail.isPending ? (
        <p className="flex items-center gap-2 text-slate-500" role="status">
          <Spinner /> Loading tracking events…
        </p>
      ) : detail.isError ? (
        <Alert tone="error">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span>{shipmentErrorMessage(detail.error, 'Could not load the tracking events.')}</span>
            <Button variant="secondary" onClick={() => void detail.refetch()}>
              Try again
            </Button>
          </div>
        </Alert>
      ) : (
        <ShipmentEvents events={detail.data.events} />
      )}

      {cancelOpen && (
        <CancelShipmentDialog
          open
          busy={cancel.isPending}
          error={cancel.isError ? shipmentErrorMessage(cancel.error, 'Could not cancel the booking.') : null}
          onConfirm={onCancel}
          onClose={() => setCancelOpen(false)}
        />
      )}
    </div>
  )
}

/** One line that says what the status means for the user, and what to do next. */
function StatusNote({ shipment, cancelReason, orderStatus }: { shipment: ShipmentSummary; cancelReason: string | null; orderStatus: Order['status'] }) {
  switch (shipment.status) {
    case 'BOOKING':
      return (
        <Alert tone="info">
          <span className="inline-flex items-center gap-2">
            <Spinner /> The courier has not answered yet. SellerDesk retries automatically; this card updates by itself.
          </span>
        </Alert>
      )
    case 'CREATED':
      return <p className="text-slate-600">Booked. The order is marked shipped when the rider picks up the parcel.</p>
    case 'FAILED':
      return (
        <Alert tone="error">
          Booking failed{shipment.failureReason ? `: ${shipment.failureReason === 'COURIER_TIMEOUT' ? 'the courier never answered' : shipment.failureReason}` : ''}.
          {shipment.needsManualCheck && ' The parcel may still exist at the courier: check the courier’s merchant panel before sending again.'}
          {orderStatus === 'PACKED' && !shipment.needsManualCheck && ' Fix the order if needed and send it again.'}
        </Alert>
      )
    case 'CANCELLED':
      return <p className="text-slate-600">Booking cancelled{cancelReason ? `: ${cancelReason}` : ''}.</p>
    case 'RETURNED':
      return orderStatus === 'SHIPPED' ? (
        <Alert tone="info">The courier returned the parcel. Use “Mark returned” to confirm the return and choose which items go back on the shelf.</Alert>
      ) : null
    case 'DELIVERED':
      return shipment.codMismatch ? (
        <Alert tone="info">The courier collected a different amount than the COD. Check it with the courier’s payment report.</Alert>
      ) : null
    default:
      return null
  }
}

/** Newest first, like a courier's tracking page. Ignored events are kept for debugging, greyed. */
function ShipmentEvents({ events }: { events: ShipmentEvent[] }) {
  if (events.length === 0) return <p className="text-slate-500">No tracking events yet.</p>
  return (
    <div>
      <h3 className="mb-2 font-medium text-slate-900">Tracking</h3>
      <ol className="space-y-3 border-l border-slate-200 pl-4">
        {[...events].reverse().map((event, i) => (
          <li key={`${event.at}-${i}`} className={event.ignored ? 'relative opacity-60' : 'relative'}>
            <span
              className={`absolute -left-[21px] top-1.5 h-2.5 w-2.5 rounded-full ring-4 ring-white ${event.ignored ? 'bg-slate-300' : 'bg-indigo-500'}`}
              aria-hidden="true"
            />
            <p className="text-slate-900">
              <span className="font-medium">{event.status ? SHIPMENT_STATUS_LABELS[event.status] : (event.providerStatus ?? 'Update')}</span>
              {event.status && event.providerStatus && <span className="text-slate-500"> ({event.providerStatus})</span>}
              {event.ignored && <span className="text-slate-500"> · ignored</span>}
            </p>
            {event.note && <p className="text-slate-600">{event.note}</p>}
            <p className="text-xs text-slate-500">
              {formatDateTime(event.at)} · {EVENT_SOURCE_LABELS[event.source]}
            </p>
          </li>
        ))}
      </ol>
    </div>
  )
}

function EarlierAttempts({ shipments }: { shipments: ShipmentSummary[] }) {
  return (
    <details className="mt-4 border-t border-slate-100 pt-3 text-sm">
      <summary className="cursor-pointer font-medium text-slate-700">Earlier bookings ({shipments.length})</summary>
      <ul className="mt-2 space-y-1">
        {shipments.map((s) => (
          <li key={s.id} className="flex flex-wrap items-center gap-2 text-slate-600">
            <ShipmentStatusBadge status={s.status} />
            <span>{COURIER_LABELS[s.provider]}</span>
            {s.trackingCode && <span className="font-mono text-xs">{s.trackingCode}</span>}
            <span className="text-xs text-slate-500">{formatDateTime(s.createdAt)}</span>
            {s.failureReason && <span className="text-xs text-red-700">{s.failureReason}</span>}
          </li>
        ))}
      </ul>
    </details>
  )
}
