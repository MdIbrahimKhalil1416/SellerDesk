import { cn } from '../../../lib/cn'
import type { ShipmentStatus } from '../../../types/shipment'
import { SHIPMENT_STATUS_LABELS, SHIPMENT_STATUS_STYLES } from '../shipmentLabels'

export function ShipmentStatusBadge({ status, className }: { status: ShipmentStatus; className?: string }) {
  return (
    <span className={cn('inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1', SHIPMENT_STATUS_STYLES[status], className)}>
      {SHIPMENT_STATUS_LABELS[status]}
    </span>
  )
}

/** Small warning chips for a list row or card. */
export function ShipmentFlags({ codMismatch, needsManualCheck }: { codMismatch: boolean; needsManualCheck: boolean }) {
  if (!codMismatch && !needsManualCheck) return null
  return (
    <span className="inline-flex flex-wrap gap-1">
      {codMismatch && <span className="rounded-full bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-800 ring-1 ring-amber-200">COD mismatch</span>}
      {needsManualCheck && <span className="rounded-full bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-800 ring-1 ring-amber-200">Check courier panel</span>}
    </span>
  )
}
