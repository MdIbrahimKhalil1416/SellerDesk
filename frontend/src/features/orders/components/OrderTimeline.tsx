import { Alert } from '../../../components/ui/Alert'
import { Button } from '../../../components/ui/Button'
import { Spinner } from '../../../components/ui/Spinner'
import { formatDateTime, STATUS_LABELS } from '../orderLabels'
import { orderErrorMessage } from '../orderErrors'
import { useOrderTimeline } from '../queries'

/** Friendly names for the fields an edit can change (backend names in `fields`). */
const FIELD_LABELS: Record<string, string> = {
  customerName: 'customer name',
  deliveryAddress: 'delivery address',
  items: 'items',
  discount: 'discount',
  deliveryCharge: 'delivery charge',
  advancePaid: 'advance',
  source: 'source',
  note: 'note',
}

/** Status changes and edits of one order, oldest first. */
export function OrderTimeline({ orderId }: { orderId: string }) {
  const timeline = useOrderTimeline(orderId)

  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4 sm:p-5">
      <h2 className="font-semibold text-slate-900">Timeline</h2>
      {timeline.isPending ? (
        <p className="mt-3 flex items-center gap-2 text-sm text-slate-500" role="status">
          <Spinner /> Loading…
        </p>
      ) : timeline.isError ? (
        <Alert tone="error" className="mt-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span>{orderErrorMessage(timeline.error, 'Could not load the timeline.')}</span>
            <Button variant="secondary" onClick={() => void timeline.refetch()}>
              Try again
            </Button>
          </div>
        </Alert>
      ) : (
        <ol className="mt-4 space-y-4 border-l border-slate-200 pl-4">
          {timeline.data.map((entry, i) => (
            <li key={`${entry.at}-${i}`} className="relative">
              <span className="absolute -left-[21px] top-1.5 h-2.5 w-2.5 rounded-full bg-indigo-500 ring-4 ring-white" aria-hidden="true" />
              <p className="text-sm text-slate-900">
                {entry.type === 'STATUS' ? (
                  entry.from ? (
                    <>
                      <span className="font-medium">{STATUS_LABELS[entry.to ?? 'PENDING']}</span>
                      <span className="text-slate-500"> (was {STATUS_LABELS[entry.from]})</span>
                    </>
                  ) : (
                    <span className="font-medium">Order created</span>
                  )
                ) : (
                  <>
                    <span className="font-medium">Edited</span>
                    {entry.fields && entry.fields.length > 0 && (
                      <span className="text-slate-500">: {entry.fields.map((f) => FIELD_LABELS[f] ?? f).join(', ')}</span>
                    )}
                  </>
                )}
              </p>
              {entry.summary && <p className="text-sm text-slate-600">{entry.summary}</p>}
              {entry.note && <p className="text-sm text-slate-600">“{entry.note}”</p>}
              <p className="text-xs text-slate-500">
                {formatDateTime(entry.at)} · {entry.by.name}
              </p>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
