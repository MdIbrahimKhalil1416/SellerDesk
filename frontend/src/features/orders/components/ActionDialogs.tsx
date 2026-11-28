import { useState, type FormEvent } from 'react'
import { Alert } from '../../../components/ui/Alert'
import { Button } from '../../../components/ui/Button'
import { Modal } from '../../../components/ui/Modal'
import { TextArea } from '../../../components/ui/TextArea'
import { TextField } from '../../../components/ui/TextField'
import type { OrderItem, ReturnRequest, ShipRequest } from '../../../types/order'

/** Backend limit for reasons and notes on a status change. */
const MAX_REASON = 500

function DialogButtons({ busy, confirmLabel, danger, onCancel }: { busy: boolean; confirmLabel: string; danger?: boolean; onCancel: () => void }) {
  return (
    <div className="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
      <Button variant="secondary" onClick={onCancel} disabled={busy}>
        Back
      </Button>
      <Button
        type="submit"
        loading={busy}
        className={danger ? 'bg-red-600 hover:bg-red-500 focus-visible:outline-red-600 disabled:bg-red-400' : undefined}
      >
        {confirmLabel}
      </Button>
    </div>
  )
}

/**
 * Cancel with a required reason. Used for one order and for a bulk cancel.
 *
 * @param stockNote true when stock is reserved (CONFIRMED / PACKED): tell the user it is released
 */
export function CancelDialog({ open, title, stockNote, busy, error, onConfirm, onClose }: {
  open: boolean
  title: string
  stockNote?: boolean
  busy: boolean
  error?: string | null
  onConfirm: (reason: string) => void
  onClose: () => void
}) {
  const [reason, setReason] = useState('')
  const [touched, setTouched] = useState(false)
  const trimmed = reason.trim()
  const invalid = !trimmed ? 'Enter a reason' : trimmed.length > MAX_REASON ? `At most ${MAX_REASON} characters` : undefined

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (!invalid) onConfirm(trimmed)
  }

  return (
    <Modal open={open} title={title} onClose={onClose} busy={busy}>
      <form onSubmit={onSubmit} noValidate className="space-y-3">
        {stockNote && <p className="text-sm text-slate-600">The reserved stock goes back to available.</p>}
        {error && <Alert tone="error">{error}</Alert>}
        <TextArea
          label="Reason"
          rows={3}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Customer stopped answering"
          error={touched ? invalid : undefined}
          hint="Saved in the order history."
        />
        <DialogButtons busy={busy} confirmLabel="Cancel order" danger onCancel={onClose} />
      </form>
    </Modal>
  )
}

/** Courier name and tracking id are optional free text (courier integration is Phase 2). */
export function ShipDialog({ open, busy, error, bulkCount, onConfirm, onClose }: {
  open: boolean
  busy: boolean
  error?: string | null
  /** Bulk: how many orders; tracking ids differ per parcel, so only the courier is asked. */
  bulkCount?: number
  onConfirm: (body: ShipRequest) => void
  onClose: () => void
}) {
  const [courierName, setCourierName] = useState('')
  const [trackingId, setTrackingId] = useState('')

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    onConfirm({ courierName: courierName.trim() || undefined, trackingId: trackingId.trim() || undefined })
  }

  return (
    <Modal open={open} title={bulkCount ? `Mark ${bulkCount} orders shipped` : 'Mark shipped'} onClose={onClose} busy={busy}>
      <form onSubmit={onSubmit} noValidate className="space-y-3">
        <p className="text-sm text-slate-600">The reserved units leave the shelf (on hand goes down).</p>
        {error && <Alert tone="error">{error}</Alert>}
        <TextField label="Courier (optional)" placeholder="Steadfast, Pathao…" maxLength={50} value={courierName} onChange={(e) => setCourierName(e.target.value)} />
        {!bulkCount && (
          <TextField label="Tracking id (optional)" maxLength={100} value={trackingId} onChange={(e) => setTrackingId(e.target.value)} />
        )}
        <DialogButtons busy={busy} confirmLabel="Mark shipped" onCancel={onClose} />
      </form>
    </Modal>
  )
}

/** Return with a reason; each item is put back on the shelf unless it is marked damaged. */
export function ReturnDialog({ open, items, busy, error, onConfirm, onClose }: {
  open: boolean
  items: OrderItem[]
  busy: boolean
  error?: string | null
  onConfirm: (body: ReturnRequest) => void
  onClose: () => void
}) {
  const [reason, setReason] = useState('')
  const [touched, setTouched] = useState(false)
  const [damaged, setDamaged] = useState<Set<string>>(new Set())
  const trimmed = reason.trim()
  const invalid = !trimmed ? 'Enter a reason' : trimmed.length > MAX_REASON ? `At most ${MAX_REASON} characters` : undefined

  function toggle(variantId: string) {
    setDamaged((current) => {
      const next = new Set(current)
      if (next.has(variantId)) next.delete(variantId)
      else next.add(variantId)
      return next
    })
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (invalid) return
    onConfirm({ reason: trimmed, items: [...damaged].map((variantId) => ({ variantId, restock: false })) })
  }

  return (
    <Modal open={open} title="Mark returned" onClose={onClose} busy={busy}>
      <form onSubmit={onSubmit} noValidate className="space-y-3">
        {error && <Alert tone="error">{error}</Alert>}
        <TextArea label="Reason" rows={2} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Customer refused the parcel" error={touched ? invalid : undefined} />
        <fieldset>
          <legend className="text-sm font-medium text-slate-700">Back on the shelf?</legend>
          <p className="text-xs text-slate-500">Untick damaged items: they are not added to stock again.</p>
          <ul className="mt-2 divide-y divide-slate-100 rounded-lg border border-slate-200">
            {items.map((item) => (
              <li key={item.variantId}>
                <label className="flex items-center gap-3 px-3 py-2 text-sm">
                  <input
                    type="checkbox"
                    checked={!damaged.has(item.variantId)}
                    onChange={() => toggle(item.variantId)}
                    className="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium text-slate-900">{item.productName}</span>
                    <span className="text-xs text-slate-500">
                      {item.variantName} · {item.sku} · ×{item.quantity}
                    </span>
                  </span>
                </label>
              </li>
            ))}
          </ul>
        </fieldset>
        <DialogButtons busy={busy} confirmLabel="Mark returned" onCancel={onClose} />
      </form>
    </Modal>
  )
}
