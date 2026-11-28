import { useState, type FormEvent } from 'react'
import { Alert } from '../../../components/ui/Alert'
import { Button } from '../../../components/ui/Button'
import { Modal } from '../../../components/ui/Modal'
import { Select } from '../../../components/ui/Select'
import { TextArea } from '../../../components/ui/TextArea'
import { TextField } from '../../../components/ui/TextField'
import { cn } from '../../../lib/cn'
import { fieldErrorsOf } from '../../../lib/apiError'
import type { AdjustmentReason, StockChangeResponse, StockRow } from '../../../types/inventory'
import { inventoryErrorMessage } from '../inventoryErrors'
import { newRequestId, REASON_LABELS } from '../labels'
import { useAdjust, useRestock } from '../queries'

/** Backend limits (docs/modules/INVENTORY.md §1). */
const MAX_QUANTITY = 100_000
const MAX_NOTE = 200

interface DialogProps {
  /** The variant to change; the dialog is open while this is set. */
  row: StockRow | null
  onClose: () => void
  /** Called after a successful change with a short message for the page. */
  onDone: (message: string) => void
}

/** "12" → 12; null for anything that is not a whole number from 1 to 100,000. */
function parseQuantity(text: string): number | null {
  const trimmed = text.trim()
  if (!/^\d+$/.test(trimmed)) return null
  const n = Number(trimmed)
  return n >= 1 && n <= MAX_QUANTITY ? n : null
}

const QUANTITY_ERROR = `Enter a whole number from 1 to ${MAX_QUANTITY.toLocaleString('en-IN')}`

/** Who / what is being changed, shown at the top of both dialogs. */
function VariantSummary({ row }: { row: StockRow }) {
  return (
    <div className="mb-4 rounded-lg bg-slate-50 px-3 py-2.5 text-sm">
      <p className="font-medium text-slate-900">
        {row.productName} · {row.variantName}
      </p>
      <p className="text-slate-500">
        SKU {row.sku} · On hand {row.onHand} · Reserved {row.reserved} · <span className="font-medium text-slate-700">Available {row.available}</span>
      </p>
    </div>
  )
}

// ==== Restock ===========================================================================

export function RestockDialog({ row, onClose, onDone }: DialogProps) {
  return (
    <Modal open={row !== null} title="Add stock" onClose={onClose}>
      {/* key: a fresh form (and a fresh request id) every time the dialog opens for a variant. */}
      {row && <RestockForm key={row.variantId} row={row} onClose={onClose} onDone={onDone} />}
    </Modal>
  )
}

function RestockForm({ row, onClose, onDone }: { row: StockRow; onClose: () => void; onDone: (message: string) => void }) {
  const restock = useRestock(row.variantId)
  const [quantity, setQuantity] = useState('')
  const [note, setNote] = useState('')
  // Same values → same id, so a retry after a timeout is applied only once. New values → new id.
  const [requestId, setRequestId] = useState(newRequestId)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)

  const parsed = parseQuantity(quantity)

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    const clientErrors: Record<string, string> = {}
    if (parsed === null) clientErrors.quantity = QUANTITY_ERROR
    setErrors(clientErrors)
    if (parsed === null) return
    restock.mutate(
      { quantity: parsed, note: note.trim() || null, requestId },
      {
        onSuccess: (result: StockChangeResponse) => {
          const label = result.movement.type === 'INITIAL' ? 'opening stock' : 'stock'
          onDone(`Added ${parsed} to ${row.sku} as ${label}. On hand now: ${result.stock.onHand}.`)
          onClose()
        },
        onError: (e) => {
          setErrors(fieldErrorsOf(e))
          setFormError(inventoryErrorMessage(e, 'Could not add the stock.'))
        },
      },
    )
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      <VariantSummary row={row} />
      {formError && <Alert tone="error">{formError}</Alert>}
      <TextField
        label="Units to add"
        inputMode="numeric"
        autoComplete="off"
        autoFocus
        placeholder="e.g. 10"
        value={quantity}
        onChange={(e) => {
          setQuantity(e.target.value)
          setRequestId(newRequestId())
          setErrors((er) => ({ ...er, quantity: '' }))
        }}
        error={errors.quantity || undefined}
        hint={parsed !== null ? `On hand after: ${row.onHand + parsed}` : 'Whole units, e.g. a supplier delivery.'}
      />
      <TextArea
        label="Note (optional)"
        rows={2}
        maxLength={MAX_NOTE}
        placeholder="e.g. Delivery from supplier #12"
        value={note}
        onChange={(e) => {
          setNote(e.target.value)
          setRequestId(newRequestId())
        }}
        error={errors.note || undefined}
      />
      {row.onHand === 0 && row.reserved === 0 && (
        <p className="text-xs text-slate-500">The first stock added to a variant is recorded as its opening stock.</p>
      )}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={restock.isPending}>
          Cancel
        </Button>
        <Button type="submit" loading={restock.isPending}>
          Add stock
        </Button>
      </div>
    </form>
  )
}

// ==== Adjust ============================================================================

export function AdjustDialog({ row, onClose, onDone }: DialogProps) {
  return (
    <Modal open={row !== null} title="Adjust stock" onClose={onClose}>
      {row && <AdjustForm key={row.variantId} row={row} onClose={onClose} onDone={onDone} />}
    </Modal>
  )
}

type Direction = 'REMOVE' | 'ADD'

function AdjustForm({ row, onClose, onDone }: { row: StockRow; onClose: () => void; onDone: (message: string) => void }) {
  const adjust = useAdjust(row.variantId)
  const [direction, setDirection] = useState<Direction>('REMOVE')
  const [quantity, setQuantity] = useState('')
  const [reason, setReason] = useState<AdjustmentReason | ''>('')
  const [note, setNote] = useState('')
  const [requestId, setRequestId] = useState(newRequestId)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)

  const parsed = parseQuantity(quantity)
  const change = parsed === null ? null : direction === 'REMOVE' ? -parsed : parsed

  /** Any edit: clear that field's error and use a new request id. */
  function edited(field: string) {
    setRequestId(newRequestId())
    setErrors((er) => ({ ...er, [field]: '' }))
  }

  function validate(): Record<string, string> {
    const e: Record<string, string> = {}
    if (parsed === null) e.quantity = QUANTITY_ERROR
    else if (direction === 'REMOVE' && parsed > row.available) {
      e.quantity =
        row.reserved > 0
          ? `Only ${row.available} can be removed: ${row.reserved} are reserved for confirmed orders.`
          : `Only ${row.available} are on hand.`
    }
    if (!reason) e.reasonCode = 'Choose a reason'
    if (reason === 'OTHER' && !note.trim()) e.note = 'Explain the reason in a few words'
    return e
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFormError(null)
    const clientErrors = validate()
    setErrors(clientErrors)
    if (Object.keys(clientErrors).length > 0 || change === null || !reason) return
    adjust.mutate(
      { quantityChange: change, reasonCode: reason, note: note.trim() || null, requestId },
      {
        onSuccess: (result) => {
          const verb = change < 0 ? `Removed ${-change} from` : `Added ${change} to`
          onDone(`${verb} ${row.sku} (${REASON_LABELS[reason].toLowerCase()}). Available now: ${result.stock.available}.`)
          onClose()
        },
        onError: (e) => {
          // The backend calls the field quantityChange; the form shows one quantity input.
          setErrors(fieldErrorsOf(e, { quantityChange: 'quantity' }))
          setFormError(inventoryErrorMessage(e, 'Could not adjust the stock.'))
        },
      },
    )
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      <VariantSummary row={row} />
      {formError && <Alert tone="error">{formError}</Alert>}

      <fieldset>
        <legend className="mb-1.5 block text-sm font-medium text-slate-700">Change</legend>
        <div className="grid grid-cols-2 gap-2" role="radiogroup">
          {(['REMOVE', 'ADD'] as const).map((d) => (
            <label
              key={d}
              className={cn(
                'flex cursor-pointer items-center justify-center gap-2 rounded-lg border px-3 py-2.5 text-sm font-medium',
                direction === d ? 'border-indigo-500 bg-indigo-50 text-indigo-700' : 'border-slate-300 text-slate-700 hover:bg-slate-50',
              )}
            >
              <input
                type="radio"
                name="direction"
                value={d}
                checked={direction === d}
                onChange={() => {
                  setDirection(d)
                  edited('quantity')
                }}
                className="sr-only"
              />
              {d === 'REMOVE' ? 'Remove units' : 'Add units'}
            </label>
          ))}
        </div>
      </fieldset>

      <TextField
        label={direction === 'REMOVE' ? 'Units to remove' : 'Units to add'}
        inputMode="numeric"
        autoComplete="off"
        autoFocus
        placeholder="e.g. 2"
        value={quantity}
        onChange={(e) => {
          setQuantity(e.target.value)
          edited('quantity')
        }}
        error={errors.quantity || undefined}
        hint={
          change !== null
            ? `On hand after: ${row.onHand + change} · Available after: ${row.available + change}`
            : direction === 'REMOVE'
              ? `Up to ${row.available} (reserved units cannot be removed).`
              : 'Whole units.'
        }
      />
      <div>
        <Select
          label="Reason"
          value={reason}
          onChange={(e) => {
            setReason(e.target.value as AdjustmentReason | '')
            edited('reasonCode')
          }}
          options={[{ value: '', label: 'Choose a reason…' }, ...Object.entries(REASON_LABELS).map(([value, label]) => ({ value, label }))]}
          aria-invalid={errors.reasonCode ? true : undefined}
        />
        {errors.reasonCode && <p className="mt-1.5 text-sm text-red-600">{errors.reasonCode}</p>}
      </div>
      <TextArea
        label={reason === 'OTHER' ? 'Note' : 'Note (optional)'}
        rows={2}
        maxLength={MAX_NOTE}
        placeholder="e.g. Water damage in the store room"
        value={note}
        onChange={(e) => {
          setNote(e.target.value)
          edited('note')
        }}
        error={errors.note || undefined}
      />
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={adjust.isPending}>
          Cancel
        </Button>
        <Button type="submit" loading={adjust.isPending}>
          Save adjustment
        </Button>
      </div>
    </form>
  )
}
