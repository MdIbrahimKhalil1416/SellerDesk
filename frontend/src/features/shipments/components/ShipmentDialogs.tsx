import { useState, type FormEvent } from 'react'
import { Alert } from '../../../components/ui/Alert'
import { Button } from '../../../components/ui/Button'
import { Modal } from '../../../components/ui/Modal'
import { Select } from '../../../components/ui/Select'
import { TextArea } from '../../../components/ui/TextArea'
import type { CourierCode, SendShipmentRequest } from '../../../types/shipment'
import { BULK_BATCH_SIZE, COURIER_OPTIONS, MAX_NOTE, MAX_REASON } from '../shipmentLabels'

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
 * Pick a courier (and, for one order, a note for the rider). Bulk: `bulkCount` packed orders are
 * sent; `skipped` selected orders are not packed and are left out.
 */
export function SendToCourierDialog({ open, busy, error, errorDetails, bulkCount, skipped, progress, onConfirm, onClose }: {
  open: boolean
  busy: boolean
  error?: string | null
  /** e.g. the fields a courier rejected ("Phone: Invalid phone number"). */
  errorDetails?: string[]
  bulkCount?: number
  skipped?: number
  /** Bulk progress while sending: "20 of 45". */
  progress?: { done: number; total: number } | null
  onConfirm: (body: SendShipmentRequest) => void
  onClose: () => void
}) {
  const [provider, setProvider] = useState<CourierCode>(COURIER_OPTIONS[0]?.value ?? 'MOCK')
  const [note, setNote] = useState('')
  const [touched, setTouched] = useState(false)
  const trimmed = note.trim()
  const invalid = trimmed.length > MAX_NOTE ? `At most ${MAX_NOTE} characters` : undefined

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (!invalid) onConfirm({ provider, note: trimmed || undefined })
  }

  return (
    <Modal open={open} title={bulkCount ? `Send ${bulkCount} orders to a courier` : 'Send to courier'} onClose={onClose} busy={busy}>
      <form onSubmit={onSubmit} noValidate className="space-y-3">
        <p className="text-sm text-slate-600">
          The order stays packed until the rider picks up the parcel; then it is marked shipped and the stock leaves the shelf.
        </p>
        {!!skipped && (
          <p className="text-sm text-amber-700">
            {skipped} selected order{skipped === 1 ? ' is' : 's are'} not packed and will be left out.
          </p>
        )}
        {error && (
          <Alert tone="error">
            {error}
            {errorDetails && errorDetails.length > 0 && (
              <ul className="mt-1 list-disc pl-5">
                {errorDetails.map((d) => (
                  <li key={d}>{d}</li>
                ))}
              </ul>
            )}
          </Alert>
        )}
        <Select label="Courier" value={provider} onChange={(e) => setProvider(e.target.value as CourierCode)} options={COURIER_OPTIONS} disabled={busy} />
        {!bulkCount && (
          <TextArea
            label="Note for the rider (optional)"
            rows={2}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="Call before delivery"
            error={touched ? invalid : undefined}
            disabled={busy}
          />
        )}
        {busy && progress && progress.total > BULK_BATCH_SIZE && (
          <p className="text-sm text-slate-600" role="status">
            Sent {progress.done} of {progress.total}…
          </p>
        )}
        <DialogButtons busy={busy} confirmLabel={bulkCount ? `Send ${bulkCount}` : 'Send to courier'} onCancel={onClose} />
      </form>
    </Modal>
  )
}

/** Cancel a booking before pickup, with a required reason. */
export function CancelShipmentDialog({ open, busy, error, onConfirm, onClose }: {
  open: boolean
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
    <Modal open={open} title="Cancel the courier booking?" onClose={onClose} busy={busy}>
      <form onSubmit={onSubmit} noValidate className="space-y-3">
        <p className="text-sm text-slate-600">The order stays packed and can be sent again.</p>
        {error && <Alert tone="error">{error}</Alert>}
        <TextArea
          label="Reason"
          rows={3}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Customer asked to deliver next week"
          error={touched ? invalid : undefined}
        />
        <DialogButtons busy={busy} confirmLabel="Cancel booking" danger onCancel={onClose} />
      </form>
    </Modal>
  )
}
